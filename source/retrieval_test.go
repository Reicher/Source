package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

type testEmbedder struct {
	mu                sync.Mutex
	id                string
	revision          string
	dimensions        int
	calls             int
	failure           error
	panicValue        any
	transientFailures int
	successDelay      time.Duration
	maximumInputBytes int
}

func (e *testEmbedder) Identity() ModelIdentity {
	return ModelIdentity{ID: e.id, Revision: e.revision}
}

func (e *testEmbedder) Dimensions() int { return e.dimensions }

func (e *testEmbedder) Embed(_ context.Context, input []string) ([][]float32, error) {
	e.mu.Lock()
	e.calls++
	transientFailure := e.transientFailures > 0
	if transientFailure {
		e.transientFailures--
	}
	e.mu.Unlock()
	if e.panicValue != nil {
		panic(e.panicValue)
	}
	if e.failure != nil {
		return nil, e.failure
	}
	if transientFailure {
		return nil, errors.New("transient runtime failure")
	}
	if e.successDelay > 0 {
		time.Sleep(e.successDelay)
	}
	result := make([][]float32, len(input))
	for index, text := range input {
		if e.maximumInputBytes > 0 && len(text) > e.maximumInputBytes {
			return nil, fmt.Errorf("embedding input is %d bytes; limit is %d", len(text), e.maximumInputBytes)
		}
		lower := strings.ToLower(text)
		vector := []float32{0.01, 0.01, 0.01}
		switch {
		case strings.Contains(lower, "quantum") || strings.Contains(lower, "entanglement") || strings.Contains(lower, "nonlocal correlation"):
			vector = []float32{1, 0, 0}
		case strings.Contains(lower, "bread") || strings.Contains(lower, "sourdough") || strings.Contains(lower, "fermented loaf"):
			vector = []float32{0, 1, 0}
		case strings.Contains(lower, "apollo") || strings.Contains(lower, "moon landing"):
			vector = []float32{0, 0, 1}
		}
		result[index] = vector[:e.dimensions]
	}
	return result, nil
}

func TestRetrievalWorkerMarksRetryAsRebuilding(t *testing.T) {
	root := t.TempDir()
	_, silver, _ := publishedRetrievalFixture(t, root)
	embedder := &testEmbedder{
		id: "embedding", revision: "retry", dimensions: 3, transientFailures: 1, successDelay: 250 * time.Millisecond,
	}
	retrieval, err := newRetrievalService(root+"/silver", silver, embedder)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer func() {
		cancel()
		_ = retrieval.close()
	}()
	retrieval.start(ctx)

	sawRetryBuilding := false
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		status := retrieval.currentStatus()
		if embedder.callCount() >= 2 && status.State == "rebuilding" && status.Error == "" {
			sawRetryBuilding = true
		}
		if status.State == "ready" {
			if !sawRetryBuilding {
				t.Fatal("retry kept stale failed state until completion")
			}
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("retrieval retry did not become ready: %+v", retrieval.currentStatus())
}

func TestRetrievalWorkerContainsRepresentationPanics(t *testing.T) {
	root := t.TempDir()
	_, silver, _ := publishedRetrievalFixture(t, root)
	retrieval, err := newRetrievalService(root+"/silver", silver, &testEmbedder{
		id: "embedding", revision: "panic", dimensions: 3, panicValue: "runtime exploded",
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer func() {
		cancel()
		_ = retrieval.close()
	}()
	retrieval.start(ctx)

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		status := retrieval.currentStatus()
		if status.State == "failed" && strings.Contains(status.Error, "runtime exploded") {
			rows, err := retrieval.lexicalSearch(context.Background(), "Apollo", 2)
			if err != nil || len(rows) != 1 {
				t.Fatalf("contained panic damaged lexical retrieval: rows=%+v err=%v", rows, err)
			}
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("retrieval worker did not expose contained panic: %+v", retrieval.currentStatus())
}

func (e *testEmbedder) callCount() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.calls
}

func publishedRetrievalFixture(t *testing.T, root string) (*bronzeStore, *silverService, bronzeItem) {
	t.Helper()
	bronze := newBronzeStore(root + "/bronze")
	silver, err := newSilverServiceWithModel(root+"/silver", bronze, nil)
	if err != nil {
		t.Fatal(err)
	}
	attachSilverQueue(silver, bronze)
	item := putSilverBronze(t, bronze, "10000000-0000-4000-8000-000000000001", "research.md", "text/markdown", 1,
		"# Research notes\n\nQuantum entanglement links particles across distance.\n\nApollo reached the Moon in 1969.\n\nSourdough bread uses a fermented starter.")
	if !silver.processNext(context.Background()) {
		t.Fatal("Silver did not process retrieval fixture")
	}
	if len(silver.snapshot().Sources) != 1 {
		t.Fatal("Silver fixture was not published")
	}
	return bronze, silver, item
}

func TestRetrievalKeepsDistinctObservationsThatShareEvidence(t *testing.T) {
	snapshot := silverSnapshot{
		Sources:  []silverSource{{BronzeSourceID: "source", BronzeContentSHA256: "hash", Title: "Shared", Mime: "text/plain"}},
		Evidence: []silverEvidence{{ID: "evidence", BronzeSourceID: "source", BronzeContentSHA256: "hash"}},
		Observations: []silverObservation{
			{ID: "observation-a", Kind: "text-block", Payload: json.RawMessage(`{"text":"alpha"}`), EvidenceIDs: []string{"evidence"}, Producer: silverProducer{ProcessorID: silverExtractionID, ProcessorVersion: silverExtractionVersion}},
			{ID: "observation-b", Kind: "markdown-heading", Payload: json.RawMessage(`{"text":"beta"}`), EvidenceIDs: []string{"evidence"}, Producer: silverProducer{ProcessorID: silverExtractionID, ProcessorVersion: silverExtractionVersion}},
		},
	}
	chunks, err := retrievalChunksFromSnapshot(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	values := chunks["source"]
	if len(values) != 2 || values[0].ID == values[1].ID || values[0].ObservationID == values[1].ObservationID {
		t.Fatalf("shared Evidence collapsed distinct observations: %+v", values)
	}
}

func TestRetrievalKeepsCompoundCSVContentAtRecordGranularity(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	content := "Name,Notes\nJonas Sandvall,\"Birthday: 1986-08-27\nPhone: 073-512 61 77\nCity: Stockholm\"\n"
	item := putSilverBronze(t, bronze, "67676767-6767-4676-8676-676767676767", "contacts.csv", "text/csv", 1, content)
	silver, err := newSilverServiceWithModel(root+"/silver", bronze, nil)
	if err != nil {
		t.Fatal(err)
	}
	silver.processNext(context.Background())
	chunks, err := retrievalChunksFromSnapshot(silver.snapshot())
	if err != nil {
		t.Fatal(err)
	}
	values := chunks[item.ID]
	if len(values) != 2 {
		t.Fatalf("atomic Evidence fragmented or duplicated retrieval chunks: %+v", values)
	}
	var row *retrievalChunk
	for index := range values {
		if values[index].Selector["row"] == 2 {
			row = &values[index]
		}
	}
	if row == nil || row.Selector["kind"] != "table-row" {
		t.Fatalf("record retrieval chunk lost row provenance: %+v", values)
	}
	for _, expected := range []string{"Jonas Sandvall", "Birthday", "1986-08-27", "Phone", "073-512 61 77", "Stockholm"} {
		if !strings.Contains(row.Text, expected) {
			t.Fatalf("retrieval lost %q from compound CSV record: %q", expected, row.Text)
		}
	}
}

func TestRetrievalSplitsOversizedObservationsBeforeEmbedding(t *testing.T) {
	root := t.TempDir()
	_, silver, item := publishedRetrievalFixture(t, root)
	largeValue := strings.Repeat("å", retrievalMaximumChunkBytes*2)
	payload, err := json.Marshal(map[string]any{"path": "/large", "value": largeValue})
	if err != nil {
		t.Fatal(err)
	}
	silver.mu.Lock()
	dataset := silver.state.Published[item.ID]
	dataset.Observations = append(dataset.Observations, silverObservation{
		ID: "oversized-observation", Kind: "parsed-json-value", Payload: payload,
		EvidenceIDs: []string{dataset.Evidence[0].ID},
		Producer:    silverProducer{ProcessorID: silverExtractionID, ProcessorVersion: silverExtractionVersion},
	})
	silver.state.Published[item.ID] = dataset
	silver.mu.Unlock()

	embedder := &testEmbedder{
		id: "embedding", revision: "bounded", dimensions: 3, maximumInputBytes: retrievalMaximumChunkBytes,
	}
	retrieval, err := newRetrievalService(root+"/silver", silver, embedder)
	if err != nil {
		t.Fatal(err)
	}
	defer retrieval.close()
	if err := retrieval.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	var parts, maximumBytes int
	if err := retrieval.db.QueryRow(`SELECT count(*), max(length(CAST(text AS BLOB)))
		FROM retrieval_chunks WHERE observation_id = ?`, "oversized-observation").Scan(&parts, &maximumBytes); err != nil {
		t.Fatal(err)
	}
	status, err := retrieval.readStatus()
	if err != nil {
		t.Fatal(err)
	}
	if parts < 2 || maximumBytes > retrievalMaximumChunkBytes || status.State != "ready" || status.Chunks != status.Embeddings {
		t.Fatalf("oversized observation was not safely embedded: parts=%d maximum=%d status=%+v", parts, maximumBytes, status)
	}
}

func TestRetrievalIndexesSilverEvidenceAndSearchesLexicalVectorAndHybrid(t *testing.T) {
	root := t.TempDir()
	_, silver, item := publishedRetrievalFixture(t, root)
	embedder := &testEmbedder{id: "test-embedding", revision: "1", dimensions: 3}
	retrieval, err := newRetrievalService(root+"/silver", silver, embedder)
	if err != nil {
		t.Fatal(err)
	}
	defer retrieval.close()
	if err := retrieval.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}

	status, err := retrieval.readStatus()
	if err != nil {
		t.Fatal(err)
	}
	if status.State != "ready" || status.Chunks != 4 || status.Embeddings != 4 ||
		status.EmbeddingProducer.ModelID != "test-embedding" || status.EmbeddingProducer.ModelRevision != "1" {
		t.Fatalf("unexpected retrieval status: %+v", status)
	}
	var sqliteChunks, ftsChunks, vectorChunks int
	if err := retrieval.db.QueryRow(`SELECT count(*) FROM retrieval_chunks`).Scan(&sqliteChunks); err != nil {
		t.Fatal(err)
	}
	if err := retrieval.db.QueryRow(`SELECT count(*) FROM retrieval_fts`).Scan(&ftsChunks); err != nil {
		t.Fatal(err)
	}
	if err := retrieval.db.QueryRow(`SELECT count(*) FROM retrieval_vec`).Scan(&vectorChunks); err != nil {
		t.Fatal(err)
	}
	if sqliteChunks != 4 || ftsChunks != sqliteChunks || vectorChunks != sqliteChunks {
		t.Fatalf("incomplete SQLite representations: chunks=%d fts=%d vec=%d", sqliteChunks, ftsChunks, vectorChunks)
	}

	lexical, err := retrieval.lexicalSearch(context.Background(), "Apollo", 10)
	if err != nil || len(lexical) != 1 {
		t.Fatalf("lexical search: results=%+v err=%v", lexical, err)
	}
	semantic, err := retrieval.vectorSearch(context.Background(), []float32{1, 0, 0}, 10)
	if err != nil || len(semantic) != 4 || semantic[0].distance > 0.001 {
		t.Fatalf("vector search: results=%+v err=%v", semantic, err)
	}
	result, err := retrieval.search(context.Background(), retrievalRequest{Query: "Apollo moon landing", Limit: 3})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Results) == 0 || result.Results[0].LexicalRank == 0 || result.Results[0].SemanticRank == 0 {
		t.Fatalf("hybrid result did not combine both channels: %+v", result)
	}
	top := result.Results[0]
	if top.BronzeSourceID != item.ID || top.BronzeContentSHA256 != item.Hash || top.EvidenceID == "" ||
		top.EvidenceSelector["kind"] != "utf8-byte-range" ||
		top.EvidenceProducer.ProcessorID != silverExtractionID ||
		top.ChunkProcessor.ProcessorID != retrievalChunkProcessorID ||
		top.EmbeddingProcessor.ModelRevision != "1" {
		t.Fatalf("retrieval provenance incomplete: %+v", top)
	}

	semanticOnly, err := retrieval.search(context.Background(), retrievalRequest{Query: "nonlocal correlation", Limit: 1})
	if err != nil || len(semanticOnly.Results) != 1 || semanticOnly.Results[0].SemanticRank != 1 ||
		semanticOnly.Results[0].LexicalRank != 0 || !strings.Contains(semanticOnly.Results[0].Text, "Quantum") {
		t.Fatalf("semantic retrieval did not recover paraphrase: result=%+v err=%v", semanticOnly, err)
	}
}

func TestRetrievalEmbeddingModelChangeRebuildsOnlyVectorsAndPersistsAcrossRestart(t *testing.T) {
	root := t.TempDir()
	_, silver, _ := publishedRetrievalFixture(t, root)
	first := &testEmbedder{id: "embedding", revision: "one", dimensions: 3}
	retrieval, err := newRetrievalService(root+"/silver", silver, first)
	if err != nil {
		t.Fatal(err)
	}
	if err := retrieval.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	var firstChunkID string
	var firstRowID int64
	if err := retrieval.db.QueryRow(`SELECT rowid, chunk_id FROM retrieval_chunks ORDER BY rowid LIMIT 1`).Scan(&firstRowID, &firstChunkID); err != nil {
		t.Fatal(err)
	}
	if err := retrieval.close(); err != nil {
		t.Fatal(err)
	}
	for _, suffix := range []string{"-wal", "-shm"} {
		if err := os.WriteFile(retrieval.path+suffix, []byte("stale sidecar"), 0600); err != nil {
			t.Fatal(err)
		}
	}

	second := &testEmbedder{id: "embedding", revision: "two", dimensions: 3}
	restarted, err := newRetrievalService(root+"/silver", silver, second)
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.close()
	if err := restarted.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	for _, suffix := range []string{"-wal", "-shm"} {
		if _, err := os.Stat(restarted.path + suffix); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("stale SQLite sidecar %s survived restart: %v", suffix, err)
		}
	}
	var journalMode string
	if err := restarted.db.QueryRow(`PRAGMA journal_mode`).Scan(&journalMode); err != nil || journalMode != "delete" {
		t.Fatalf("retrieval SQLite journal mode = %q, err=%v", journalMode, err)
	}
	var nextChunkID string
	var nextRowID int64
	if err := restarted.db.QueryRow(`SELECT rowid, chunk_id FROM retrieval_chunks ORDER BY rowid LIMIT 1`).Scan(&nextRowID, &nextChunkID); err != nil {
		t.Fatal(err)
	}
	status, err := restarted.readStatus()
	if err != nil {
		t.Fatal(err)
	}
	if nextRowID != firstRowID || nextChunkID != firstChunkID || status.EmbeddingProducer.ModelRevision != "two" ||
		status.State != "ready" || second.callCount() == 0 {
		t.Fatalf("embedding-only rebuild changed chunks or missed model revision: before=%d/%s after=%d/%s status=%+v calls=%d",
			firstRowID, firstChunkID, nextRowID, nextChunkID, status, second.callCount())
	}
	result, err := restarted.search(context.Background(), retrievalRequest{Query: "Apollo", Limit: 1})
	if err != nil || len(result.Results) != 1 {
		t.Fatalf("persistent retrieval failed after restart: result=%+v err=%v", result, err)
	}
}

func TestRetrievalRebuildsDerivedDatabaseForSchemaChange(t *testing.T) {
	root := t.TempDir()
	_, silver, _ := publishedRetrievalFixture(t, root)
	embedder := &testEmbedder{id: "embedding", revision: "one", dimensions: 3}
	retrieval, err := newRetrievalService(root+"/silver", silver, embedder)
	if err != nil {
		t.Fatal(err)
	}
	if err := retrieval.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := retrieval.db.Exec(`UPDATE retrieval_schema SET version = 1`); err != nil {
		t.Fatal(err)
	}
	if err := retrieval.close(); err != nil {
		t.Fatal(err)
	}

	rebuilt, err := newRetrievalService(root+"/silver", silver, embedder)
	if err != nil {
		t.Fatal(err)
	}
	defer rebuilt.close()
	var version, chunks int
	if err := rebuilt.db.QueryRow(`SELECT version FROM retrieval_schema`).Scan(&version); err != nil {
		t.Fatal(err)
	}
	if err := rebuilt.db.QueryRow(`SELECT count(*) FROM retrieval_chunks`).Scan(&chunks); err != nil {
		t.Fatal(err)
	}
	if version != retrievalSchemaVersion || chunks != 0 {
		t.Fatalf("derived schema was not rebuilt: version=%d chunks=%d", version, chunks)
	}
	if err := rebuilt.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	status, err := rebuilt.readStatus()
	if err != nil || status.State != "ready" || status.Chunks == 0 {
		t.Fatalf("rebuilt schema did not reconcile: status=%+v err=%v", status, err)
	}
}

func TestRetrievalRebuildsCorruptDerivedDatabase(t *testing.T) {
	root := t.TempDir()
	_, silver, _ := publishedRetrievalFixture(t, root)
	path := root + "/silver/retrieval.sqlite"
	if err := os.WriteFile(path, []byte("not a SQLite database"), 0600); err != nil {
		t.Fatal(err)
	}
	retrieval, err := newRetrievalService(root+"/silver", silver, &testEmbedder{id: "embedding", revision: "one", dimensions: 3})
	if err != nil {
		t.Fatal(err)
	}
	defer retrieval.close()
	if err := retrieval.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	status, err := retrieval.readStatus()
	if err != nil || status.State != "ready" || status.Chunks == 0 || status.Chunks != status.Embeddings {
		t.Fatalf("corrupt derived database was not rebuilt: status=%+v err=%v", status, err)
	}
}

func TestRetrievalDeletesAllRepresentationsWithBronze(t *testing.T) {
	root := t.TempDir()
	bronze, silver, item := publishedRetrievalFixture(t, root)
	retrieval, err := newRetrievalService(root+"/silver", silver, &testEmbedder{id: "embedding", revision: "1", dimensions: 3})
	if err != nil {
		t.Fatal(err)
	}
	defer retrieval.close()
	if err := retrieval.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	tombstone := item
	tombstone.Revision = 2
	tombstone.Deleted = true
	tombstone.Hash = ""
	tombstone.Size = 0
	tombstone.Modified = 2
	if response := bronzeRequest(t, bronze, http.MethodDelete, tombstone, nil); response.Code != http.StatusOK {
		t.Fatalf("delete Bronze: %d: %s", response.Code, response.Body.String())
	}
	if err := retrieval.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	status, err := retrieval.readStatus()
	if err != nil {
		t.Fatal(err)
	}
	var fts, vectors int
	if err := retrieval.db.QueryRow(`SELECT count(*) FROM retrieval_fts`).Scan(&fts); err != nil {
		t.Fatal(err)
	}
	if err := retrieval.db.QueryRow(`SELECT count(*) FROM retrieval_vec`).Scan(&vectors); err != nil {
		t.Fatal(err)
	}
	if status.Chunks != 0 || status.Embeddings != 0 || fts != 0 || vectors != 0 {
		t.Fatalf("deleted Bronze remained retrievable: status=%+v fts=%d vectors=%d", status, fts, vectors)
	}
	result, err := retrieval.search(context.Background(), retrievalRequest{Query: "Apollo"})
	if err != nil || len(result.Results) != 0 {
		t.Fatalf("deleted Bronze influenced retrieval: result=%+v err=%v", result, err)
	}
}

func TestRetrievalReplacesChangedSilverChunksWithoutLeavingOldIndexRows(t *testing.T) {
	root := t.TempDir()
	_, silver, item := publishedRetrievalFixture(t, root)
	retrieval, err := newRetrievalService(root+"/silver", silver, &testEmbedder{id: "embedding", revision: "1", dimensions: 3})
	if err != nil {
		t.Fatal(err)
	}
	defer retrieval.close()
	if err := retrieval.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}

	silver.mu.Lock()
	dataset := silver.state.Published[item.ID]
	changed := false
	for index := range dataset.Observations {
		observation := &dataset.Observations[index]
		if observation.Producer.ProcessorID == silverExtractionID && strings.Contains(string(observation.Payload), "Apollo") {
			observation.Payload = json.RawMessage(`{"text":"Gemini replaced the prior mission fragment."}`)
			observation.Producer.ProcessorVersion = "replacement-test"
			changed = true
		}
	}
	silver.state.Published[item.ID] = dataset
	silver.mu.Unlock()
	if !changed {
		t.Fatal("fixture did not contain the expected deterministic observation")
	}
	if err := retrieval.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	oldRows, err := retrieval.lexicalSearch(context.Background(), "Apollo", 10)
	if err != nil {
		t.Fatal(err)
	}
	newRows, err := retrieval.lexicalSearch(context.Background(), "Gemini", 10)
	if err != nil {
		t.Fatal(err)
	}
	status, err := retrieval.readStatus()
	if err != nil {
		t.Fatal(err)
	}
	if len(oldRows) != 0 || len(newRows) != 1 || status.Chunks != status.Embeddings {
		t.Fatalf("changed Silver left stale retrieval rows: old=%+v new=%+v status=%+v", oldRows, newRows, status)
	}
}

func TestRetrievalKeepsLexicalIndexWhenEmbeddingUnavailableOrFails(t *testing.T) {
	root := t.TempDir()
	_, silver, _ := publishedRetrievalFixture(t, root)
	retrieval, err := newRetrievalService(root+"/silver", silver, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := retrieval.reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	result, err := retrieval.search(context.Background(), retrievalRequest{Query: "Apollo", Limit: 2})
	if err != nil || len(result.Results) != 1 || result.SemanticState != "unavailable" || result.SemanticError == "" {
		t.Fatalf("lexical fallback unavailable: result=%+v err=%v", result, err)
	}
	if err := retrieval.close(); err != nil {
		t.Fatal(err)
	}

	failing := &testEmbedder{id: "embedding", revision: "broken", dimensions: 3, failure: errors.New("runtime offline")}
	retrieval, err = newRetrievalService(root+"/silver", silver, failing)
	if err != nil {
		t.Fatal(err)
	}
	defer retrieval.close()
	if err := retrieval.reconcile(context.Background()); err == nil || !strings.Contains(err.Error(), "runtime offline") {
		t.Fatalf("embedding failure was hidden: %v", err)
	}
	status, err := retrieval.readStatus()
	if err != nil {
		t.Fatal(err)
	}
	if status.State != "failed" || !strings.Contains(status.Error, "runtime offline") || status.Chunks == 0 {
		t.Fatalf("embedding failure state lost: %+v", status)
	}
	lexical, err := retrieval.lexicalSearch(context.Background(), "Apollo", 2)
	if err != nil || len(lexical) != 1 {
		t.Fatalf("embedding failure damaged FTS: rows=%+v err=%v", lexical, err)
	}
}

func TestRetrievalEndpointRequiresPairedSelfAndReturnsDebuggableChannels(t *testing.T) {
	t.Setenv("SOURCE_MODEL_URL", "")
	t.Setenv("SOURCE_EMBEDDING_URL", "")
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
	handler := testLanHandler(t, identity)
	body := []byte(`{"query":"anything","limit":5}`)

	untrusted := httptest.NewRecorder()
	handler.ServeHTTP(untrusted, httptest.NewRequest(http.MethodPost, "/v1/retrieval", bytes.NewReader(body)))
	if untrusted.Code != http.StatusUnauthorized {
		t.Fatalf("untrusted retrieval status: %d", untrusted.Code)
	}
	request := httptest.NewRequest(http.MethodPost, "/v1/retrieval", bytes.NewReader(body))
	request.TLS = &tls.ConnectionState{PeerCertificates: []*x509.Certificate{leaf}}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("trusted retrieval: %d: %s", response.Code, response.Body.String())
	}
	var result retrievalResponse
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.Results == nil || result.SemanticState == "" || result.SemanticError == "" {
		t.Fatalf("retrieval response lacks channel diagnostics: %+v", result)
	}
}

func TestRetrievalSetupStatusDoesNotQuerySQLite(t *testing.T) {
	t.Setenv("SOURCE_MODEL_URL", "")
	t.Setenv("SOURCE_EMBEDDING_URL", "")
	identity, err := loadIdentity(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	testLanHandler(t, identity)
	request := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:8081/retrieval/status", nil)
	request.Host = "127.0.0.1:8081"
	request.RemoteAddr = "127.0.0.1:12345"
	response := httptest.NewRecorder()
	identity.setupHandler("127.0.0.1:8081").ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("retrieval status: %d: %s", response.Code, response.Body.String())
	}
	var status retrievalStatus
	if err := json.Unmarshal(response.Body.Bytes(), &status); err != nil {
		t.Fatal(err)
	}
	if status.State == "" || status.ChunkProcessor.ProcessorID != retrievalChunkProcessorID {
		t.Fatalf("incomplete retrieval status: %+v", status)
	}
}

func TestHTTPEmbedderUsesOpenAICompatibleLocalEndpoint(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/v1/embeddings" {
			t.Fatalf("unexpected embedding request: %s %s", r.Method, r.URL.Path)
		}
		var request struct {
			Model string   `json:"model"`
			Input []string `json:"input"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Fatal(err)
		}
		if request.Model != "embedding-model" || len(request.Input) != 2 {
			t.Fatalf("unexpected embedding payload: %+v", request)
		}
		writeJSON(w, map[string]any{"data": []any{
			map[string]any{"index": 1, "embedding": []float32{0, 1, 0}},
			map[string]any{"index": 0, "embedding": []float32{1, 0, 0}},
		}})
	}))
	defer server.Close()
	embedder, err := newHTTPEmbedder(server.URL, "embedding-model", "revision", 3, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	vectors, err := embedder.Embed(context.Background(), []string{"first", "second"})
	if err != nil || len(vectors) != 2 || vectors[0][0] != 1 || vectors[1][1] != 1 {
		t.Fatalf("OpenAI embedding response mapping failed: vectors=%v err=%v", vectors, err)
	}
	if _, err := newHTTPEmbedder("https://models.example.test", "model", "revision", 3, time.Minute); err == nil {
		t.Fatal("accepted a non-local embedding endpoint")
	}
}

func TestLocalSemanticRuntimeAlsoImplementsGenericLLM(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			Model    string            `json:"model"`
			Messages []semanticMessage `json:"messages"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Fatal(err)
		}
		if request.Model != "local-llm" || len(request.Messages) != 1 {
			t.Fatalf("unexpected LLM request: %+v", request)
		}
		writeJSON(w, map[string]any{"choices": []any{
			map[string]any{"message": map[string]any{"role": "assistant", "content": "local answer"}},
		}})
	}))
	defer server.Close()
	processor, err := newHTTPSemanticModel(server.URL, "local-llm", "weights-1")
	if err != nil {
		t.Fatal(err)
	}
	llm, ok := processor.(LLM)
	if !ok {
		t.Fatal("local semantic runtime does not implement LLM")
	}
	response, err := llm.Complete(context.Background(), LLMRequest{
		Messages: []semanticMessage{{Role: "user", Content: "hello"}}, MaximumTokens: 32,
	})
	if err != nil || response.Content != "local answer" || llm.Identity().Revision != "weights-1" {
		t.Fatalf("generic LLM boundary failed: response=%+v identity=%+v err=%v", response, llm.Identity(), err)
	}
}
