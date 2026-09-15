package syncmodel

import (
	"strings"
	"testing"
)

func TestRevisionIDUsesCanonicalIdentityAndExcludesCreatedAt(t *testing.T) {
	createdAt := int64(100)
	revision := Revision{
		ObjectKey: ObjectKey{
			ProfileID:  "11111111-1111-4111-8111-111111111111",
			Collection: "conversations",
			ObjectID:   "22222222-2222-4222-8222-222222222222",
		},
		Kind:              "content",
		ParentRevisionIDs: []string{strings.Repeat("a", 64)},
		Payload: &Payload{
			Format: "source-client-conversation", FormatVersion: 3,
			ByteCount: 123, PlaintextSHA256: strings.Repeat("b", 64),
		},
		CreatedAtMillis: &createdAt,
	}
	first, err := RevisionID(revision)
	if err != nil {
		t.Fatal(err)
	}
	later := int64(999)
	revision.CreatedAtMillis = &later
	second, err := RevisionID(revision)
	if err != nil {
		t.Fatal(err)
	}
	if first != second {
		t.Fatalf("producer timestamp changed identity: %s != %s", first, second)
	}
	if first != "c83cd3b151d4489784da0152c8ea27409a050e2ed0518c8a24a5ad95fa9625ec" {
		t.Fatalf("revision id = %s", first)
	}
}

func TestRevisionRejectsNonCanonicalParentsAndUnicode(t *testing.T) {
	revision := Revision{
		ObjectKey: ObjectKey{
			ProfileID:  "11111111-1111-4111-8111-111111111111",
			Collection: "conversations", ObjectID: "e\u0301",
		},
		Kind:              "tombstone",
		ParentRevisionIDs: []string{strings.Repeat("b", 64), strings.Repeat("a", 64)},
	}
	if _, err := RevisionID(revision); err == nil {
		t.Fatal("non-NFC object identifier and unsorted parents were accepted")
	}
}
