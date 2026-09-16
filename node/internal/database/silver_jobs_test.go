package database

import (
	"strings"
	"testing"

	"source.local/node/internal/security"
)

func TestAuthorContextCreatesDistinctRefinementGeneration(t *testing.T) {
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
	user, _, err := db.CreatePairedUser("Robin", 1024*1024, "recovery", "envelope", NewClient{
		ID: "client-a", DisplayName: "Phone", PublicKey: "public", CredentialHash: "credential", ProtocolVersion: 1,
	}, 1)
	if err != nil {
		t.Fatal(err)
	}
	source := SilverRefinementSource{
		SourceID: "note-1", Name: "note.txt", SourceType: "note",
		ContentSHA256: strings.Repeat("a", 64), Plaintext: "I started Source", AcceptedAt: 2,
	}
	first, changed, err := db.AcceptSilverRefinementJob(
		user.ID, "11111111-1111-4111-8111-111111111111", strings.Repeat("b", 64), source,
		"processor", "1", "model", "silver", 1, 2,
	)
	if err != nil || !changed || first.AuthoredBySelf {
		t.Fatalf("first job = %#v changed=%v error=%v", first, changed, err)
	}
	if err = db.SaveSilverRefinementCheckpoint(first, 0, strings.Repeat("c", 64), `{}`, 3); err != nil {
		t.Fatal(err)
	}

	source.AuthoredBySelf = true
	source.AcceptedAt = 4
	second, changed, err := db.AcceptSilverRefinementJob(
		user.ID, "22222222-2222-4222-8222-222222222222", strings.Repeat("d", 64), source,
		"processor", "1", "model", "silver", 1, 4,
	)
	if err != nil || !changed || !second.AuthoredBySelf || second.JobID == first.JobID {
		t.Fatalf("second job = %#v changed=%v error=%v", second, changed, err)
	}
	checkpoints, err := db.SilverRefinementCheckpoints(second)
	if err != nil || len(checkpoints) != 0 {
		t.Fatalf("author-context checkpoints = %#v error=%v", checkpoints, err)
	}
	if current, err := db.IsCurrentSilverRefinementSource(user.ID, source.SourceID, source.ContentSHA256, false); err != nil || current {
		t.Fatalf("old author context remained current: current=%v error=%v", current, err)
	}
	if current, err := db.IsCurrentSilverRefinementSource(user.ID, source.SourceID, source.ContentSHA256, true); err != nil || !current {
		t.Fatalf("new author context is not current: current=%v error=%v", current, err)
	}
}
