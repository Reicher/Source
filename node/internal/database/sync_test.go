package database

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"testing"

	"source.local/node/internal/security"
	"source.local/node/internal/syncmodel"
)

func TestScopedCursorAcknowledgementsAndEpochPrecondition(t *testing.T) {
	db, err := Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	identity, err := security.GenerateNodeIdentity()
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.InitializeNode("Node", identity, "password", 1); err != nil {
		t.Fatal(err)
	}
	user, client, err := db.CreatePairedUser("User", 1024, "recovery", "envelope", NewClient{
		ID: "client", DisplayName: "Client", PublicKey: "public", CredentialHash: "credential", ProtocolVersion: 1,
	}, 1)
	if err != nil {
		t.Fatal(err)
	}
	state, err := db.SyncState(user.ID, identity.NodeID)
	if err != nil {
		t.Fatal(err)
	}
	body := []byte("content")
	sum := sha256.Sum256(body)
	createdAt := int64(10)
	revision := syncmodel.Revision{
		ObjectKey: syncmodel.ObjectKey{ProfileID: user.ID, Collection: "conversations", ObjectID: "55555555-5555-4555-8555-555555555555"},
		Kind:      "content", ParentRevisionIDs: []string{},
		Payload:         &syncmodel.Payload{Format: "test", FormatVersion: 1, ByteCount: int64(len(body)), PlaintextSHA256: hex.EncodeToString(sum[:])},
		CreatedAtMillis: &createdAt,
	}
	revision.RevisionID, err = syncmodel.RevisionID(revision)
	if err != nil {
		t.Fatal(err)
	}
	mutation := syncmodel.Mutation{
		ContractVersion: 1, OperationID: "11111111-1111-4111-8111-111111111111",
		OriginID: client.ID, OriginEpoch: "22222222-2222-4222-8222-222222222222",
		OriginSequence: 1, ExpectedAuthorityEpoch: state.AuthorityEpoch, Revision: revision,
	}
	digest, err := syncmodel.MutationDigest(mutation)
	if err != nil {
		t.Fatal(err)
	}
	receipt, created, err := db.CommitSyncMutation(user.ID, identity.NodeID, digest, revision.RevisionID, mutation)
	if err != nil || !created || receipt.CommitSequence != 1 {
		t.Fatalf("commit = %#v %v %v", receipt, created, err)
	}
	cursor := syncmodel.Cursor{AuthorityNodeID: identity.NodeID, AuthorityEpoch: state.AuthorityEpoch, CommitSequence: 1}
	if err = db.AcknowledgeSyncCursor(user.ID, client.ID, "conversations", "55555555-5555-4555-8555-555555555555", cursor, 11); err != nil {
		t.Fatal(err)
	}
	if err = db.AcknowledgeSyncCursor(user.ID, client.ID, "library-manifests", "66666666-6666-4666-8666-666666666666", cursor, 12); err != nil {
		t.Fatal(err)
	}
	var cursorCount int
	if err = db.sql.QueryRow(`SELECT COUNT(*) FROM client_sync_cursors WHERE user_id=? AND client_id=?`, user.ID, client.ID).Scan(&cursorCount); err != nil || cursorCount != 2 {
		t.Fatalf("scoped cursor count = %d, error %v", cursorCount, err)
	}

	newEpoch := "33333333-3333-4333-8333-333333333333"
	if _, err = db.sql.Exec(`UPDATE profile_sync_state SET authority_epoch=?,next_commit_sequence=1 WHERE user_id=?`, newEpoch, user.ID); err != nil {
		t.Fatal(err)
	}
	mutation.OperationID = "44444444-4444-4444-8444-444444444444"
	mutation.OriginSequence = 2
	digest, _ = syncmodel.MutationDigest(mutation)
	_, _, err = db.CommitSyncMutation(user.ID, identity.NodeID, digest, revision.RevisionID, mutation)
	var failure SyncFailure
	if !errors.As(err, &failure) || failure.Code != "authority_epoch_changed" {
		t.Fatalf("stale epoch mutation error = %v", err)
	}
}
