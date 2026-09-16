package silver

import (
	"strings"
	"testing"
)

const testSelfEntityID = "11111111-1111-4111-8111-111111111111"

func TestProfileIdentityAnchorsAuthoredFirstPersonToSelf(t *testing.T) {
	source := Source{
		ID: "note-1", Name: "note.txt", SourceType: "note", ContentSHA256: strings.Repeat("a", 64),
		Text: "I started Source", AuthoredBySelf: true,
	}
	generation := testGeneration(t, source, 10, map[string]any{
		"subject":   map[string]any{"name": "I", "type": "person"},
		"predicate": "started",
		"object":    map[string]any{"name": "Source", "type": "project"},
	})
	dataset, err := replaceGeneration(EmptyDataset(), ProfileContext{
		UserID: "profile-1", DisplayName: "Robin", SelfEntityID: testSelfEntityID,
	}, source, generation, 10)
	if err != nil {
		t.Fatal(err)
	}
	if !hasEntity(dataset, testSelfEntityID) || !hasScalarClaim(dataset, testSelfEntityID, "name", "Robin") ||
		!hasScalarClaim(dataset, testSelfEntityID, "entity-type", "person") {
		t.Fatalf("Self anchor is incomplete: %#v", dataset)
	}
	if hasScalarClaim(dataset, testSelfEntityID, "name", "I") {
		t.Fatalf("first-person syntax became a Self name: %#v", dataset.Claims)
	}
	started := claimByPredicate(dataset, "started")
	if started == nil || started.SubjectEntityID != testSelfEntityID || started.ObjectEntityID == nil {
		t.Fatalf("authored first-person observation did not resolve to Self: %#v", started)
	}
}

func TestUnverifiedFirstPersonDoesNotResolveToSelf(t *testing.T) {
	source := Source{
		ID: "import-1", Name: "import.txt", SourceType: "file", ContentSHA256: strings.Repeat("b", 64),
		Text: "I started Source",
	}
	generation := testGeneration(t, source, 10, map[string]any{
		"subject":   map[string]any{"name": "I", "type": "person"},
		"predicate": "started",
		"object":    map[string]any{"name": "Source", "type": "project"},
	})
	dataset, err := replaceGeneration(EmptyDataset(), ProfileContext{
		UserID: "profile-1", DisplayName: "Robin", SelfEntityID: testSelfEntityID,
	}, source, generation, 10)
	if err != nil {
		t.Fatal(err)
	}
	if claimByPredicate(dataset, "started") != nil {
		t.Fatalf("unverified first-person observation was resolved: %#v", dataset.Claims)
	}
	for _, claim := range dataset.Claims {
		if claim.Predicate == "name" && claim.Value == "I" {
			t.Fatalf("unverified first-person mention created an entity: %#v", claim)
		}
	}
}

func TestLaterSourceRefinesPersonIdentityAndStrengthensClaim(t *testing.T) {
	profile := ProfileContext{UserID: "profile-1", DisplayName: "Robin", SelfEntityID: testSelfEntityID}
	firstSource := Source{ID: "notes", Name: "notes.txt", SourceType: "file", ContentSHA256: strings.Repeat("c", 64), Text: "Martin knows Source"}
	firstGeneration := testGeneration(t, firstSource, 10, map[string]any{
		"subject":   map[string]any{"name": "Martin", "type": "person"},
		"predicate": "knows",
		"object":    map[string]any{"name": "Source", "type": "project"},
	})
	dataset, err := replaceGeneration(EmptyDataset(), profile, firstSource, firstGeneration, 10)
	if err != nil {
		t.Fatal(err)
	}
	martinID := subjectForScalarClaim(dataset, "name", "Martin")
	if martinID == "" {
		t.Fatal("first source did not create Martin")
	}

	secondSource := Source{ID: "contacts", Name: "contacts.csv", SourceType: "file", ContentSHA256: strings.Repeat("d", 64), Text: "Martin Broström knows Source"}
	secondGeneration := testGeneration(t, secondSource, 20, map[string]any{
		"subject":   map[string]any{"name": "Martin Broström", "type": "person"},
		"predicate": "knows",
		"object":    map[string]any{"name": "Source", "type": "project"},
	})
	dataset, err = replaceGeneration(dataset, profile, secondSource, secondGeneration, 20)
	if err != nil {
		t.Fatal(err)
	}
	if got := subjectForScalarClaim(dataset, "name", "Martin Broström"); got != martinID {
		t.Fatalf("full name resolved to %q, want existing Martin entity %q", got, martinID)
	}
	foundStrengthened := false
	for _, claim := range dataset.Claims {
		if claim.State == "active" && claim.Predicate == "knows" && claim.SubjectEntityID == martinID && len(claim.SupportingObservationIDs) == 2 {
			foundStrengthened = true
		}
	}
	if !foundStrengthened {
		t.Fatalf("cross-source claim did not preserve both observations: %#v", dataset.Claims)
	}
}

func TestNewSilverReconsidersPreviouslyUnresolvedObservation(t *testing.T) {
	oldSource := Source{ID: "journal", Name: "journal.txt", SourceType: "note", ContentSHA256: strings.Repeat("e", 64), Text: "I started Source", AuthoredBySelf: true}
	oldGeneration := testGeneration(t, oldSource, 5, map[string]any{
		"subject":        map[string]any{"name": "I", "type": "person"},
		"predicate":      "started",
		"object":         map[string]any{"name": "Source", "type": "project"},
		"authoredBySelf": true,
	})
	prior := Dataset{
		Evidence: oldGeneration.Evidence, Observations: oldGeneration.Observations,
		RemovedSources: map[string]int64{}, ModifiedAtMillis: 5,
	}
	newSource := Source{ID: "empty", Name: "empty.txt", SourceType: "file", ContentSHA256: strings.Repeat("f", 64), Text: "No facts"}
	newGeneration := testGeneration(t, newSource, 10, nil)
	dataset, err := replaceGeneration(prior, ProfileContext{
		UserID: "profile-1", DisplayName: "Robin", SelfEntityID: testSelfEntityID,
	}, newSource, newGeneration, 10)
	if err != nil {
		t.Fatal(err)
	}
	started := claimByPredicate(dataset, "started")
	if started == nil || started.SubjectEntityID != testSelfEntityID {
		t.Fatalf("previously unresolved observation was not reconsidered: %#v", dataset.Claims)
	}
}

func testGeneration(t *testing.T, source Source, now int64, payload map[string]any) extractedGeneration {
	t.Helper()
	evidence, err := NewEvidence(source.ID, source.ContentSHA256)
	if err != nil {
		t.Fatal(err)
	}
	producer := Producer{ProcessorID: ExtractionProcessorID, ProcessorVersion: ExtractionVersion}
	observations := []Observation{}
	if payload != nil {
		kind := "relationship-candidate"
		if _, relationship := payload["object"]; !relationship {
			kind = "attribute-candidate"
		}
		if source.AuthoredBySelf {
			payload["authoredBySelf"] = true
		}
		observation, createErr := NewObservation(kind, payload, []string{evidence.ID}, nil, producer, now)
		if createErr != nil {
			t.Fatal(createErr)
		}
		observations = append(observations, observation)
	}
	complete, err := NewObservation(ExtractionCompleteKind, map[string]any{
		"authoredBySelf": source.AuthoredBySelf,
	}, []string{evidence.ID}, nil, producer, now)
	if err != nil {
		t.Fatal(err)
	}
	observations = append(observations, complete)
	return extractedGeneration{Evidence: []Evidence{evidence}, Observations: observations}
}

func hasEntity(dataset Dataset, entityID string) bool {
	for _, entity := range dataset.Entities {
		if entity.ID == entityID {
			return true
		}
	}
	return false
}

func hasScalarClaim(dataset Dataset, subjectID, predicate string, value any) bool {
	for _, claim := range dataset.Claims {
		if claim.State == "active" && claim.SubjectEntityID == subjectID && claim.Predicate == predicate && claim.Value == value {
			return true
		}
	}
	return false
}

func claimByPredicate(dataset Dataset, predicate string) *Claim {
	for index := range dataset.Claims {
		if dataset.Claims[index].State == "active" && dataset.Claims[index].Predicate == predicate {
			return &dataset.Claims[index]
		}
	}
	return nil
}

func subjectForScalarClaim(dataset Dataset, predicate string, value any) string {
	for _, claim := range dataset.Claims {
		if claim.State == "active" && claim.Predicate == predicate && claim.Value == value {
			return claim.SubjectEntityID
		}
	}
	return ""
}
