package storage

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"source.local/node/internal/database"
	"source.local/node/internal/security"
)

func TestInterruptedLibraryUploadLeavesNoVisibleOrStagedItem(t *testing.T) {
	db, err := database.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	user, _, err := db.CreatePairedUser(
		"Test", 1024*1024, "recovery", "envelope",
		database.NewClient{
			ID: "client", DisplayName: "Client", PublicKey: "public",
			CredentialHash: "credential", ProtocolVersion: 1,
		},
		1,
	)
	if err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	store := New(db, root, 20, func() time.Time { return time.UnixMilli(2) })
	id, _ := security.UUID()
	body := bytes.Repeat([]byte{7}, 64)
	hash := sha256.Sum256(body)
	contentHash := strings.Repeat("a", 64)

	if _, _, err = store.PutLibraryItem(
		user.ID, id, contentHash, hex.EncodeToString(hash[:]), int64(len(body)),
		bytes.NewReader(body[:32]), 1024,
	); err == nil {
		t.Fatal("short upload was accepted")
	}
	dir := filepath.Join(root, user.StorageNamespace, "library", "items")
	staged, err := filepath.Glob(filepath.Join(dir, ".*.tmp"))
	if err != nil || len(staged) != 0 {
		t.Fatalf("interrupted upload left staging files: %v %v", staged, err)
	}
	if item, err := db.FindLibraryItem(user.ID, id); err != nil || item != nil {
		t.Fatalf("interrupted upload became visible: %#v %v", item, err)
	}

	item, created, err := store.PutLibraryItem(
		user.ID, id, contentHash, hex.EncodeToString(hash[:]), int64(len(body)),
		bytes.NewReader(body), 1024,
	)
	if err != nil || !created || item == nil {
		t.Fatalf("retry did not complete: %#v %v", item, err)
	}
}
