package storage

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"testing"
	"time"

	"source.local/node/internal/apperror"
	"source.local/node/internal/database"
	"source.local/node/internal/security"
	"source.local/node/internal/syncmodel"
)

func TestCanonicalMutationsAreIdempotentAndPreserveConcurrentHeads(t *testing.T) {
	db, err := database.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	identity, err := security.GenerateNodeIdentity()
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.InitializeNode("Test", identity, "password", 1); err != nil {
		t.Fatal(err)
	}
	user, _, err := db.CreatePairedUser("Test", 1024*1024, "recovery", "envelope", database.NewClient{
		ID: "client-a", DisplayName: "A", PublicKey: "public", CredentialHash: "credential", ProtocolVersion: 1,
	}, 1)
	if err != nil {
		t.Fatal(err)
	}
	store := New(db, t.TempDir(), 20, func() time.Time { return time.UnixMilli(10) })
	objectID := "22222222-2222-4222-8222-222222222222"

	root := testMutation(t, user.ID, "client-a", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 1, objectID, nil, []byte("root"))
	receipt, heads, created, err := store.CommitMutation(user.ID, "client-a", identity.NodeID, root, bytes.NewReader([]byte("root")), 1024)
	if err != nil || !created || receipt.CommitSequence != 1 || len(heads) != 1 {
		t.Fatalf("root commit = %#v %#v %v %v", receipt, heads, created, err)
	}
	retry, retryHeads, created, err := store.CommitMutation(user.ID, "client-a", identity.NodeID, root, bytes.NewReader([]byte("ignored retry body")), 1024)
	if err != nil || created || retry.CommitSequence != 1 || len(retryHeads) != 1 {
		t.Fatalf("retry = %#v %#v %v %v", retry, retryHeads, created, err)
	}

	conflictingOperation := root
	conflictingOperation.Revision.CreatedAtMillis = pointer(int64(11))
	if _, _, _, err = store.CommitMutation(user.ID, "client-a", identity.NodeID, conflictingOperation, bytes.NewReader([]byte("root")), 1024); errorCode(err) != "idempotency_conflict" {
		t.Fatalf("operation reuse error = %v", err)
	}

	left := testMutation(t, user.ID, "client-a", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", 2, objectID, []string{root.Revision.RevisionID}, []byte("left"))
	if _, _, _, err = store.CommitMutation(user.ID, "client-a", identity.NodeID, left, bytes.NewReader([]byte("left")), 1024); err != nil {
		t.Fatal(err)
	}
	right := testMutation(t, user.ID, "client-b", "cccccccc-cccc-4ccc-8ccc-cccccccccccc", 1, objectID, []string{root.Revision.RevisionID}, []byte("right"))
	if _, heads, _, err = store.CommitMutation(user.ID, "client-b", identity.NodeID, right, bytes.NewReader([]byte("right")), 1024); err != nil || len(heads) != 2 {
		t.Fatalf("concurrent heads = %#v %v", heads, err)
	}

	partialDelete := testTombstone(t, user.ID, "client-a", "dddddddd-dddd-4ddd-8ddd-dddddddddddd", 3, objectID, []string{left.Revision.RevisionID})
	if _, _, _, err = store.CommitMutation(user.ID, "client-a", identity.NodeID, partialDelete, bytes.NewReader(nil), 1024); errorCode(err) != "delete_conflict" {
		t.Fatalf("partial delete error = %v", err)
	}
	allHeads := []string{left.Revision.RevisionID, right.Revision.RevisionID}
	if allHeads[0] > allHeads[1] {
		allHeads[0], allHeads[1] = allHeads[1], allHeads[0]
	}
	deleted := testTombstone(t, user.ID, "client-a", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", 4, objectID, allHeads)
	if receipt, heads, created, err = store.CommitMutation(user.ID, "client-a", identity.NodeID, deleted, bytes.NewReader(nil), 1024); err != nil || !created || receipt.CommitSequence != 4 || len(heads) != 1 || heads[0].Kind != "tombstone" {
		t.Fatalf("delete = %#v %#v %v %v", receipt, heads, created, err)
	}
	resurrection := testMutation(t, user.ID, "client-b", "ffffffff-ffff-4fff-8fff-ffffffffffff", 2, objectID, []string{deleted.Revision.RevisionID}, []byte("back"))
	if _, _, _, err = store.CommitMutation(user.ID, "client-b", identity.NodeID, resurrection, bytes.NewReader([]byte("back")), 1024); errorCode(err) != "object_deleted" {
		t.Fatalf("resurrection error = %v", err)
	}
}

func testMutation(t *testing.T, profileID, originID, operationID string, sequence int64, objectID string, parents []string, body []byte) syncmodel.Mutation {
	t.Helper()
	sum := sha256.Sum256(body)
	createdAt := int64(10)
	revision := syncmodel.Revision{
		ObjectKey: syncmodel.ObjectKey{ProfileID: profileID, Collection: "conversations", ObjectID: objectID},
		Kind:      "content", ParentRevisionIDs: append([]string(nil), parents...),
		Payload:         &syncmodel.Payload{Format: "source-client-conversation", FormatVersion: 3, ByteCount: int64(len(body)), PlaintextSHA256: hex.EncodeToString(sum[:])},
		CreatedAtMillis: &createdAt,
	}
	if revision.ParentRevisionIDs == nil {
		revision.ParentRevisionIDs = []string{}
	}
	revisionID, err := syncmodel.RevisionID(revision)
	if err != nil {
		t.Fatal(err)
	}
	revision.RevisionID = revisionID
	return syncmodel.Mutation{ContractVersion: 1, OperationID: operationID, OriginID: originID, OriginEpoch: "11111111-1111-4111-8111-111111111111", OriginSequence: sequence, Revision: revision}
}

func testTombstone(t *testing.T, profileID, originID, operationID string, sequence int64, objectID string, parents []string) syncmodel.Mutation {
	t.Helper()
	revision := syncmodel.Revision{
		ObjectKey: syncmodel.ObjectKey{ProfileID: profileID, Collection: "conversations", ObjectID: objectID},
		Kind:      "tombstone", ParentRevisionIDs: append([]string(nil), parents...),
	}
	revisionID, err := syncmodel.RevisionID(revision)
	if err != nil {
		t.Fatal(err)
	}
	revision.RevisionID = revisionID
	return syncmodel.Mutation{ContractVersion: 1, OperationID: operationID, OriginID: originID, OriginEpoch: "11111111-1111-4111-8111-111111111111", OriginSequence: sequence, Revision: revision}
}

func pointer(value int64) *int64 { return &value }

func errorCode(err error) string {
	var apiError *apperror.Error
	if errors.As(err, &apiError) {
		return apiError.Code
	}
	return strings.TrimSpace(errString(err))
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
