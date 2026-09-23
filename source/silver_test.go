package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func putSilverBronze(t *testing.T, store *bronzeStore, id, title, mime string, revision int64, content string) bronzeItem {
	t.Helper()
	sum := sha256.Sum256([]byte(content))
	item := bronzeItem{
		ID: id, Revision: revision, Hash: hex.EncodeToString(sum[:]), Title: title, Mime: mime,
		Size: int64(len(content)), Created: 1, Modified: revision,
	}
	if response := bronzeRequest(t, store, http.MethodPut, item, []byte(content)); response.Code != http.StatusOK {
		t.Fatalf("put Bronze: %d: %s", response.Code, response.Body.String())
	}
	return item
}

func attachSilverQueue(service *silverService, bronze *bronzeStore) {
	bronze.onCommit = func(item bronzeItem) error {
		_, err := service.enqueue(item)
		return err
	}
}

func TestSilverQueueAndCheckpointsSurviveRestart(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	item := putSilverBronze(t, bronze, "11111111-1111-4111-8111-111111111111", "people.txt", "text/plain", 1,
		"Ada Lovelace designed a machine.\n\nGrace Hopper built compilers.")
	service, err := newSilverService(root+"/silver", bronze)
	if err != nil {
		t.Fatal(err)
	}
	if len(service.state.Jobs) != 1 || service.state.Jobs[0].State != "queued" {
		t.Fatalf("Bronze was not durably queued: %+v", service.state.Jobs)
	}

	ctx, cancel := context.WithCancel(context.Background())
	service.afterCheckpoint = func(_ string, batch int) {
		if batch == 0 {
			cancel()
		}
	}
	if service.processNext(ctx) {
		t.Fatal("cancelled processing unexpectedly reported more work")
	}
	if got := service.snapshot(); len(got.Sources) != 0 || len(got.Evidence) != 0 {
		t.Fatalf("partial checkpoints became authoritative: %+v", got)
	}
	if len(service.state.Jobs[0].Checkpoints) != 1 {
		t.Fatalf("checkpoint was not persisted: %+v", service.state.Jobs[0])
	}

	restarted, err := newSilverService(root+"/silver", bronze)
	if err != nil {
		t.Fatal(err)
	}
	if restarted.state.Jobs[0].State != "queued" || len(restarted.state.Jobs[0].Checkpoints) != 1 {
		t.Fatalf("restart did not recover queued checkpoint: %+v", restarted.state.Jobs[0])
	}
	processed := 0
	restarted.afterCheckpoint = func(_ string, _ int) { processed++ }
	if !restarted.processNext(context.Background()) {
		t.Fatal("restarted worker did not run")
	}
	if processed != 1 {
		t.Fatalf("restart repeated completed batch; processed %d batches", processed)
	}
	snapshot := restarted.snapshot()
	if len(snapshot.Sources) != 1 || snapshot.Sources[0].BronzeContentSHA256 != item.Hash {
		t.Fatalf("complete generation was not published: %+v", snapshot.Sources)
	}
	if len(snapshot.Evidence) != 2 || len(snapshot.Observations) < 6 {
		t.Fatalf("expected evidence-backed extraction and semantics: evidence=%d observations=%d", len(snapshot.Evidence), len(snapshot.Observations))
	}
	if len(snapshot.Entities) != 2 || len(snapshot.Claims) != 2 {
		t.Fatalf("generic exact-label resolution missing: entities=%d claims=%d", len(snapshot.Entities), len(snapshot.Claims))
	}
}

func TestSilverFailedSaveDoesNotExposeUnpublishedGeneration(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(filepath.Join(root, "bronze"))
	putSilverBronze(t, bronze, "77777777-7777-4777-8777-777777777777", "note.txt", "text/plain", 1, "Ada Lovelace wrote notes.")
	service, err := newSilverService(filepath.Join(root, "silver"), bronze)
	if err != nil {
		t.Fatal(err)
	}
	originalPath := service.path
	blocker := filepath.Join(root, "blocker")
	if err := os.WriteFile(blocker, nil, 0600); err != nil {
		t.Fatal(err)
	}
	service.afterCheckpoint = func(_ string, _ int) {
		service.path = filepath.Join(blocker, "state.json")
	}
	service.processNext(context.Background())
	if snapshot := service.snapshot(); len(snapshot.Sources) != 0 || len(snapshot.Evidence) != 0 {
		t.Fatalf("failed publication became authoritative: %+v", snapshot)
	}
	service.path = originalPath
	restarted, err := newSilverService(filepath.Join(root, "silver"), bronze)
	if err != nil {
		t.Fatal(err)
	}
	if len(restarted.state.Jobs) != 1 || len(restarted.state.Jobs[0].Checkpoints) != 1 {
		t.Fatalf("failed publication lost the durable checkpoint: %+v", restarted.state.Jobs)
	}
	if !restarted.processNext(context.Background()) || len(restarted.snapshot().Sources) != 1 {
		t.Fatal("failed publication did not resume from checkpoint")
	}
}

func TestSilverReconcilesCommitAfterImmediateEnqueueFailure(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(filepath.Join(root, "bronze"))
	service, err := newSilverService(filepath.Join(root, "silver"), bronze)
	if err != nil {
		t.Fatal(err)
	}
	attachSilverQueue(service, bronze)
	originalPath := service.path
	blocker := filepath.Join(root, "blocker")
	if err := os.WriteFile(blocker, nil, 0600); err != nil {
		t.Fatal(err)
	}
	service.path = filepath.Join(blocker, "state.json")
	content := "Ada Lovelace survived a queue outage."
	sum := sha256.Sum256([]byte(content))
	item := bronzeItem{ID: "99999999-9999-4999-8999-999999999999", Revision: 1,
		Hash: hex.EncodeToString(sum[:]), Title: "outage.txt", Mime: "text/plain",
		Size: int64(len(content)), Created: 1, Modified: 1}
	response := bronzeRequest(t, bronze, http.MethodPut, item, []byte(content))
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("enqueue failure status: %d", response.Code)
	}
	if _, err := bronze.load(item.ID); err != nil {
		t.Fatalf("Bronze was not persisted before enqueue failed: %v", err)
	}
	service.path = originalPath
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	service.start(ctx)
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if snapshot := service.snapshot(); len(snapshot.Sources) == 1 && snapshot.Sources[0].BronzeSourceID == item.ID {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("persisted Bronze was not reconciled into Silver without a restart")
}

func TestSilverProcessingIdentityIncludesFormatMetadata(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	item := putSilverBronze(t, bronze, "66666666-6666-4666-8666-666666666666", "data.txt", "text/plain", 1, `{"name":"Ada Lovelace"}`)
	service, err := newSilverService(root+"/silver", bronze)
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())
	item.Title = "data.json"
	item.Mime = "application/json"
	changed, err := service.enqueue(item)
	if err != nil || !changed || len(service.state.Jobs) != 2 || service.state.Jobs[1].State != "queued" {
		t.Fatalf("format metadata did not produce distinct work: changed=%v err=%v jobs=%+v", changed, err, service.state.Jobs)
	}
	if service.state.Jobs[0].Title == service.state.Jobs[1].Title || service.state.Jobs[0].Mime == service.state.Jobs[1].Mime {
		t.Fatalf("format identity was not captured in jobs: %+v", service.state.Jobs)
	}
}

func TestSilverFormatAwareParsingAndGenericFallback(t *testing.T) {
	tests := []struct {
		name, title, mime, content, kind string
	}{
		{"json", "data", "application/json; charset=utf-8", `{"person":{"name":"Ada Lovelace"}}`, "parsed-json-value"},
		{"csv", "data.csv", "text/csv", "name,email\nAda Lovelace,ada@example.test\n", "parsed-table-row"},
		{"markdown", "note.md", "text/markdown", "# Project Notes\n\nAda Lovelace drafted this.", "markdown-heading"},
		{"unknown", "archive.odd", "application/octet-stream", "Ada Lovelace left readable text.", "text-block"},
		{"malformed csv fallback", "broken.csv", "text/csv", "name\n\"unterminated", "text-block"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			item := bronzeItem{Title: test.title, Mime: test.mime}
			fragments, supported, err := parseSilverText(item, []byte(test.content))
			if err != nil || !supported {
				t.Fatalf("parse failed: supported=%v err=%v", supported, err)
			}
			found := false
			for _, fragment := range fragments {
				if fragment.Kind == test.kind {
					found = true
				}
			}
			if !found {
				t.Fatalf("missing %s in %+v", test.kind, fragments)
			}
		})
	}
}

func TestSilverBinaryBronzeDoesNotPretendToBeText(t *testing.T) {
	fragments, supported, err := parseSilverText(bronzeItem{Title: "image.bin", Mime: "application/octet-stream"}, []byte{0, 1, 2, 3})
	if err != nil || supported || fragments != nil {
		t.Fatalf("binary detection: fragments=%v supported=%v err=%v", fragments, supported, err)
	}
}

type countingReader struct {
	reader io.Reader
	read   int
}

func (r *countingReader) Read(value []byte) (int, error) {
	n, err := r.reader.Read(value)
	r.read += n
	return n, err
}

func TestSilverRejectsKnownBinaryWithoutReadingContent(t *testing.T) {
	reader := &countingReader{reader: bytes.NewReader(make([]byte, silverMaximumInputBytes+1))}
	fragments, supported, err := parseSilverReader(bronzeItem{Title: "photo.jpg", Mime: "image/jpeg"}, reader)
	if err != nil || supported || fragments != nil || reader.read != 0 {
		t.Fatalf("binary read was not bounded by metadata: read=%d supported=%v fragments=%v err=%v", reader.read, supported, fragments, err)
	}
}

func TestSilverReaderHasHardInputBound(t *testing.T) {
	reader := &countingReader{reader: bytes.NewReader(bytes.Repeat([]byte("x"), silverMaximumInputBytes+1024))}
	fragments, supported, err := parseSilverReader(bronzeItem{Title: "unknown.dat", Mime: "application/octet-stream"}, reader)
	if err != nil || supported || fragments != nil || reader.read > silverMaximumInputBytes+1 {
		t.Fatalf("input bound: read=%d supported=%v fragments=%v err=%v", reader.read, supported, fragments, err)
	}
}

func TestSilverEvidenceRangesReferToOriginalBronzeBytes(t *testing.T) {
	tests := []struct {
		name, title, mime, content string
	}{
		{"generic BOM and whitespace", "note.txt", "text/plain", "\ufeff  " + strings.Repeat("å", silverMaximumBatchBytes) + "  "},
		{"markdown whitespace", "note.md", "text/markdown", "  # Heading  \n\n  Body text  \n"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fragments, supported, err := parseSilverText(bronzeItem{Title: test.title, Mime: test.mime}, []byte(test.content))
			if err != nil || !supported || len(fragments) == 0 {
				t.Fatalf("parse: supported=%v fragments=%v err=%v", supported, fragments, err)
			}
			for _, fragment := range fragments {
				if fragment.Selector["kind"] != "utf8-byte-range" {
					continue
				}
				start := fragment.Selector["start_byte"].(int)
				end := fragment.Selector["end_byte"].(int)
				if got := test.content[start:end]; got != fragment.Text {
					t.Fatalf("range %d:%d selected %q, want %q", start, end, got, fragment.Text)
				}
			}
		})
	}
}

func TestSilverEntityAggregatesSourcesAndDeletionRemovesDerivedData(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	first := putSilverBronze(t, bronze, "33333333-3333-4333-8333-333333333333", "first.txt", "text/plain", 1, "Ada Lovelace wrote this.")
	second := putSilverBronze(t, bronze, "44444444-4444-4444-8444-444444444444", "second.txt", "text/plain", 1, "Ada Lovelace reviewed this.")
	service, err := newSilverService(root+"/silver", bronze)
	if err != nil {
		t.Fatal(err)
	}
	attachSilverQueue(service, bronze)
	service.processNext(context.Background())
	service.processNext(context.Background())
	snapshot := service.snapshot()
	if len(snapshot.Entities) != 1 || len(snapshot.Claims) != 2 || len(snapshot.Sources) != 2 {
		t.Fatalf("same exact label did not aggregate across sources: entities=%d claims=%d sources=%d", len(snapshot.Entities), len(snapshot.Claims), len(snapshot.Sources))
	}
	deleted := first
	deleted.Revision = 2
	deleted.Modified = 2
	deleted.Deleted = true
	deleted.Hash = ""
	deleted.Size = 0
	if response := bronzeRequest(t, bronze, http.MethodDelete, deleted, nil); response.Code != http.StatusOK {
		t.Fatalf("delete Bronze: %d", response.Code)
	}
	snapshot = service.snapshot()
	if len(snapshot.Sources) != 1 || snapshot.Sources[0].BronzeSourceID != second.ID || len(snapshot.Claims) != 1 {
		t.Fatalf("deletion left authoritative derived records: %+v", snapshot)
	}
}

func TestSilverSnapshotRequiresPairedSelf(t *testing.T) {
	identity, err := loadIdentity(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	client := testClientCert(t)
	leaf, err := x509.ParseCertificate(client.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	identity.state.SelfPin = fingerprint(leaf)
	handler := identity.lanHandler()

	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/v1/silver", nil))
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("untrusted snapshot: %d", response.Code)
	}

	request := httptest.NewRequest(http.MethodGet, "/v1/silver", nil)
	request.TLS = &tls.ConnectionState{PeerCertificates: []*x509.Certificate{leaf}}
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("trusted snapshot: %d: %s", response.Code, response.Body.String())
	}
	var snapshot silverSnapshot
	if err := json.Unmarshal(response.Body.Bytes(), &snapshot); err != nil {
		t.Fatal(err)
	}
	if snapshot.SchemaVersion != silverSchemaVersion {
		t.Fatalf("schema version: %d", snapshot.SchemaVersion)
	}
}

func TestSilverWorkerContinuesWithoutSelfConnection(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	service, err := newSilverService(root+"/silver", bronze)
	if err != nil {
		t.Fatal(err)
	}
	attachSilverQueue(service, bronze)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	service.start(ctx)
	putSilverBronze(t, bronze, "55555555-5555-4555-8555-555555555555", "background.txt", "text/plain", 1, "Ada Lovelace worked while Self was absent.")
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if len(service.snapshot().Sources) == 1 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("Source-owned worker did not publish without a Self request")
}
