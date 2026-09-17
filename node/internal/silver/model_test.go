package silver

import "testing"

func TestCanonicalJSONMatchesSharedRFC8785Vector(t *testing.T) {
	raw, err := marshalCanonical(map[string]any{
		"numbers":  []any{333333333.33333329, 1e30, 4.50, 2e-3, 1e-27},
		"string":   "€$\x0f\nA'B\"\\\"/",
		"literals": []any{nil, true, false},
	})
	if err != nil {
		t.Fatal(err)
	}
	want := "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27]," +
		"\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\"/\"}"
	if string(raw) != want {
		t.Fatalf("canonical JSON = %s", raw)
	}
}

func TestSilverIdentityMatchesAndroidGoldenVector(t *testing.T) {
	evidence, err := NewEvidence("source-1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	if err != nil {
		t.Fatal(err)
	}
	if evidence.ID != "1002b34a04fa56b0f2eb4da494a67006165ac901045fe9aeb183603c348f28ef" {
		t.Fatalf("evidence ID = %s", evidence.ID)
	}
	model := "model-4b"
	observation, err := NewObservation(
		"knowledge-candidates",
		map[string]any{"claims": []any{}, "entities": []any{}},
		[]string{evidence.ID}, nil,
		Producer{ProcessorID: "source.android.silver-extraction", ProcessorVersion: "2", ModelID: &model}, 100,
	)
	if err != nil {
		t.Fatal(err)
	}
	if observation.ID != "6ab9dcd554dd8a3a54597903197a027ff8b17a8c983415d0da8eede21f04b4ed" {
		t.Fatalf("observation ID = %s", observation.ID)
	}
}

func TestCanonicalSilverObjectIDMatchesAndroid(t *testing.T) {
	if got := canonicalObjectID(Collection); got != "ab385774-7835-3e2c-afce-ad2702c8f8cb" {
		t.Fatalf("canonical object ID = %s", got)
	}
}

func TestClaimRejectsSelfRelationship(t *testing.T) {
	entityID := "entity-1"
	_, err := NewClaim(
		entityID,
		"related-to",
		&entityID,
		nil,
		[]string{"observation-1"},
		nil,
		Producer{ProcessorID: ResolutionProcessorID, ProcessorVersion: ResolutionVersion},
		1,
	)
	if err == nil {
		t.Fatal("self-referential Silver Claim was accepted")
	}
}
