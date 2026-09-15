package silver

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"testing"
	"time"

	localai "source.local/node/internal/ai"
	"source.local/node/internal/database"
	"source.local/node/internal/security"
	"source.local/node/internal/storage"
)

type testAI struct{ calls int }

func (a *testAI) Status(context.Context) bool  { return true }
func (a *testAI) Capabilities() map[string]any { return map[string]any{"modelId": "test-model"} }
func (a *testAI) StreamChat(_ context.Context, _ []localai.Message, yield func(localai.Event) error) error {
	a.calls++
	return yield(localai.Event{Type: "delta", Text: `{"entities":[{"key":"e1","name":"Source","type":"project"}],"claims":[{"subjectKey":"e1","predicate":"status","value":"active","confidence":0.9,"evidenceExcerpt":"Source is active"}]}`})
}

func TestNodeRefinementIsAuthoritativeAndIdempotentAcrossReconnect(t *testing.T) {
	db, err := database.Open(":memory:")
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
	user, _, err := db.CreatePairedUser("Robin", 1024*1024, "recovery", "envelope", database.NewClient{
		ID: "client-a", DisplayName: "Phone", PublicKey: "public", CredentialHash: "credential", ProtocolVersion: 1,
	}, 1)
	if err != nil {
		t.Fatal(err)
	}
	ai := &testAI{}
	now := time.UnixMilli(1_800_000_000_000)
	store := storage.New(db, t.TempDir(), 20, func() time.Time { return now })
	service := New(db, store, ai, 1024*1024, func() time.Time { return now })

	request := refinementRequest(t, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Source is active")
	first, err := service.Refine(context.Background(), user.ID, request)
	if err != nil || !first.BronzeAccepted || !first.Refined || first.Receipt == nil || ai.calls != 1 {
		t.Fatalf("first refinement = %#v calls=%d error=%v", first, ai.calls, err)
	}
	heads, err := db.SyncHeads(user.ID, Collection, canonicalObjectID(Collection))
	if err != nil || len(heads) != 1 {
		t.Fatalf("heads = %#v error=%v", heads, err)
	}
	value, _, err := store.CanonicalPayload(user.ID, heads[0].RevisionID)
	if err != nil {
		t.Fatal(err)
	}
	dataset, err := DecodeDataset(value.Body)
	if err != nil {
		t.Fatal(err)
	}
	if len(dataset.Evidence) != 1 || len(dataset.Observations) != 2 || len(dataset.Claims) != 3 ||
		dataset.Observations[0].Producer.ProcessorID != ExtractionProcessorID {
		t.Fatalf("unexpected authoritative dataset: %#v", dataset)
	}

	// A lost response/reconnect can repeat the same operation or use a fresh one;
	// neither path invokes the model or appends a competing revision.
	service = New(db, store, ai, 1024*1024, func() time.Time { return now })
	for _, operation := range []string{
		"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
		"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
	} {
		request.OperationID = operation
		retry, retryErr := service.Refine(context.Background(), user.ID, request)
		if retryErr != nil || retry.Refined || ai.calls != 1 {
			t.Fatalf("retry = %#v calls=%d error=%v", retry, ai.calls, retryErr)
		}
	}
	heads, err = db.SyncHeads(user.ID, Collection, canonicalObjectID(Collection))
	if err != nil || len(heads) != 1 || heads[0].RevisionID != first.Receipt.RevisionID {
		t.Fatalf("reconnect changed heads = %#v error=%v", heads, err)
	}

	now = now.Add(time.Second)
	changedRequest := refinementRequest(t, "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "Source is maintained")
	changed, err := service.Refine(context.Background(), user.ID, changedRequest)
	if err != nil || !changed.Refined || ai.calls != 2 {
		t.Fatalf("reprocessing = %#v calls=%d error=%v", changed, ai.calls, err)
	}
	heads, err = db.SyncHeads(user.ID, Collection, canonicalObjectID(Collection))
	if err != nil || len(heads) != 1 || heads[0].RevisionID == first.Receipt.RevisionID ||
		len(heads[0].ParentRevisionIDs) != 1 || heads[0].ParentRevisionIDs[0] != first.Receipt.RevisionID {
		t.Fatalf("reprocessing forked history = %#v error=%v", heads, err)
	}
	refinedHead := heads[0].RevisionID
	now = now.Add(time.Second)
	removal := RemoveRequest{ContractVersion: 1, OperationID: "dddddddd-dddd-4ddd-8ddd-dddddddddddd", SourceID: "source-1"}
	removed, err := service.Remove(user.ID, removal)
	if err != nil || !removed.BronzeRemoved || !removed.SilverChanged {
		t.Fatalf("removal = %#v error=%v", removed, err)
	}
	heads, err = db.SyncHeads(user.ID, Collection, canonicalObjectID(Collection))
	if err != nil || len(heads) != 1 || len(heads[0].ParentRevisionIDs) != 1 || heads[0].ParentRevisionIDs[0] != refinedHead {
		t.Fatalf("removal forked history = %#v error=%v", heads, err)
	}
	value, _, err = store.CanonicalPayload(user.ID, heads[0].RevisionID)
	if err != nil {
		t.Fatal(err)
	}
	dataset, err = DecodeDataset(value.Body)
	if err != nil {
		t.Fatal(err)
	}
	if len(dataset.Evidence) != 0 || len(dataset.Observations) != 0 || len(dataset.Claims) != 0 ||
		dataset.RemovedSources["source-1"] == 0 {
		t.Fatalf("source remained in authoritative Silver: %#v", dataset)
	}
	retryRemoval, err := service.Remove(user.ID, removal)
	if err != nil || retryRemoval.SilverChanged {
		t.Fatalf("removal retry = %#v error=%v", retryRemoval, err)
	}
}

func refinementRequest(t *testing.T, operationID, text string) RefineRequest {
	t.Helper()
	digest := sha256.Sum256([]byte(text))
	return RefineRequest{ContractVersion: 1, OperationID: operationID, Source: Source{
		ID: "source-1", Name: "source.txt", SourceType: "file",
		ContentSHA256: hex.EncodeToString(digest[:]), Text: text,
	}}
}
