package main

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
)

func bronzeRequest(t *testing.T, store *bronzeStore, method string, item bronzeItem, body []byte) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(method, "/v1/bronze/"+item.ID, bytes.NewReader(body))
	value, _ := json.Marshal(item)
	request.Header.Set(bronzeMetaHeader, base64.RawURLEncoding.EncodeToString(value))
	response := httptest.NewRecorder()
	store.ServeHTTP(response, request)
	return response
}

func TestBronzeIdempotenceInterruptionAndTombstone(t *testing.T) {
	dir := t.TempDir()
	store := newBronzeStore(dir)
	content := []byte("offline note")
	sum := sha256.Sum256(content)
	item := bronzeItem{ID: "11111111-1111-4111-8111-111111111111", Revision: 1,
		Hash: hex.EncodeToString(sum[:]), Title: "Note", Mime: "text/plain", Size: int64(len(content)), Created: 1, Modified: 1}
	if got := bronzeRequest(t, store, http.MethodPut, item, content[:3]).Code; got != http.StatusBadRequest {
		t.Fatalf("partial upload: %d", got)
	}
	list := httptest.NewRecorder()
	store.ServeHTTP(list, httptest.NewRequest(http.MethodGet, "/v1/bronze", nil))
	if list.Body.String() != "[]\n" {
		t.Fatalf("partial upload became visible: %s", list.Body.String())
	}
	for retry := 0; retry < 2; retry++ {
		if got := bronzeRequest(t, store, http.MethodPut, item, content).Code; got != http.StatusOK {
			t.Fatalf("upload %d: %d", retry, got)
		}
	}
	store = newBronzeStore(dir)
	got, err := store.load(item.ID)
	if err != nil || got != item {
		t.Fatalf("restart lost item: %+v, %v", got, err)
	}
	deleted := item
	deleted.Revision++
	deleted.Modified++
	deleted.Deleted = true
	if got := bronzeRequest(t, store, http.MethodDelete, deleted, nil).Code; got != http.StatusOK {
		t.Fatalf("delete: %d", got)
	}
	if _, err := os.Stat(store.blobPath(item.Hash)); !os.IsNotExist(err) {
		t.Fatalf("deleted content remains on disk: %v", err)
	}
	if got := bronzeRequest(t, store, http.MethodDelete, deleted, nil).Code; got != http.StatusOK {
		t.Fatalf("delete retry: %d", got)
	}
	if got := bronzeRequest(t, store, http.MethodPut, item, content).Code; got != http.StatusConflict {
		t.Fatalf("stale revision overwrote deletion: %d", got)
	}
	store = newBronzeStore(dir)
	got, err = store.load(item.ID)
	if err != nil || !got.Deleted || got.Revision != 2 {
		t.Fatalf("restart lost tombstone: %+v, %v", got, err)
	}
}

func TestBronzeOfflineChangesBeforeFirstSync(t *testing.T) {
	store := newBronzeStore(t.TempDir())
	content := []byte("third version")
	sum := sha256.Sum256(content)
	item := bronzeItem{ID: "22222222-2222-4222-8222-222222222222", Revision: 3,
		Hash: hex.EncodeToString(sum[:]), Title: "Edited offline", Mime: "text/plain",
		Size: int64(len(content)), Created: 1, Modified: 3}
	if got := bronzeRequest(t, store, http.MethodPut, item, content).Code; got != http.StatusOK {
		t.Fatalf("offline edits: %d", got)
	}
	item.ID = "33333333-3333-4333-8333-333333333333"
	item.Revision = 2
	item.Deleted = true
	if got := bronzeRequest(t, store, http.MethodDelete, item, nil).Code; got != http.StatusOK {
		t.Fatalf("offline creation and deletion: %d", got)
	}
}

func TestBronzeSharedContentSurvivesOneDeletion(t *testing.T) {
	store := newBronzeStore(t.TempDir())
	content := []byte("shared")
	sum := sha256.Sum256(content)
	first := bronzeItem{ID: "44444444-4444-4444-8444-444444444444", Revision: 1,
		Hash: hex.EncodeToString(sum[:]), Title: "One", Mime: "text/plain", Size: int64(len(content)), Created: 1, Modified: 1}
	second := first
	second.ID = "55555555-5555-4555-8555-555555555555"
	second.Title = "Two"
	for _, item := range []bronzeItem{first, second} {
		if got := bronzeRequest(t, store, http.MethodPut, item, content).Code; got != http.StatusOK {
			t.Fatalf("store shared content: %d", got)
		}
	}
	first.Revision++
	first.Modified++
	first.Deleted = true
	first.Hash = ""
	first.Size = 0
	if got := bronzeRequest(t, store, http.MethodDelete, first, nil).Code; got != http.StatusOK {
		t.Fatalf("delete first: %d", got)
	}
	if _, err := os.Stat(store.blobPath(second.Hash)); err != nil {
		t.Fatalf("shared content was removed: %v", err)
	}
}

func TestBronzeRequiresPairedCertificate(t *testing.T) {
	i, err := loadIdentity(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	client := testClientCert(t)
	leaf, err := x509.ParseCertificate(client.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	i.state.SelfPin = fingerprint(leaf)
	if i.state.SelfPin == "" {
		t.Fatal("empty test fingerprint")
	}
	handler := i.trusted(newBronzeStore(t.TempDir()))
	request := httptest.NewRequest(http.MethodGet, "/v1/bronze", nil)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("no certificate: %d", response.Code)
	}
	request.TLS = &tls.ConnectionState{PeerCertificates: []*x509.Certificate{leaf}}
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("paired certificate: %d", response.Code)
	}
}
