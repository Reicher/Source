package silver

import (
	"context"
	"fmt"
	"strings"
	"testing"

	localai "source.local/node/internal/ai"
)

type extractionAI struct {
	messages []localai.Message
	options  localai.ChatOptions
	output   string
}

type trailingWhitespaceAI struct{ yielded int }

func (a *trailingWhitespaceAI) Status(context.Context) bool { return true }
func (a *trailingWhitespaceAI) State(context.Context) localai.RuntimeState {
	return localai.RuntimeState{Availability: "ready", Capabilities: a.Capabilities()}
}
func (a *trailingWhitespaceAI) Capabilities() map[string]any {
	return map[string]any{"modelId": "test-model"}
}
func (a *trailingWhitespaceAI) StreamChat(_ context.Context, _ []localai.Message, _ localai.ChatOptions, yield func(localai.Event) error) error {
	for _, text := range []string{`{"entities":[],"claims":[]}`, " ", " ", " "} {
		a.yielded++
		if err := yield(localai.Event{Type: "delta", Text: text}); err != nil {
			return err
		}
	}
	return nil
}

func (a *extractionAI) Status(context.Context) bool { return true }
func (a *extractionAI) State(context.Context) localai.RuntimeState {
	return localai.RuntimeState{Availability: "ready", Capabilities: a.Capabilities()}
}
func (a *extractionAI) Capabilities() map[string]any { return map[string]any{"modelId": "test-model"} }
func (a *extractionAI) StreamChat(_ context.Context, messages []localai.Message, options localai.ChatOptions, yield func(localai.Event) error) error {
	a.messages, a.options = messages, options
	return yield(localai.Event{Type: "delta", Text: a.output})
}

func TestExtractionPreservesClearReferencesWithoutGuessingAmbiguousOnes(t *testing.T) {
	prose := "Acme launched Beacon. It owns the service, and its headquarters are in Malmö. Jordan met Casey after they emailed Morgan."
	ai := &extractionAI{output: `{
		"entities":[
			{"key":"acme","name":"Acme","type":"organization"},
			{"key":"beacon","name":"Beacon","type":"service"},
			{"key":"malmo","name":"Malmö","type":"city"},
			{"key":"jordan","name":"Jordan","type":"person"},
			{"key":"casey","name":"Casey","type":"person"},
			{"key":"morgan","name":"Morgan","type":"person"}
		],
		"claims":[
			{"subjectKey":"acme","predicate":"launched","objectKey":"beacon","confidence":0.99,"evidenceExcerpt":"Acme launched Beacon"},
			{"subjectKey":"acme","predicate":"owns","objectKey":"beacon","confidence":0.95,"evidenceExcerpt":"It owns the service"},
			{"subjectKey":"acme","predicate":"headquartered-in","objectKey":"malmo","confidence":0.95,"evidenceExcerpt":"its headquarters are in Malmö"},
			{"subjectKey":"jordan","predicate":"met","objectKey":"casey","confidence":0.9,"evidenceExcerpt":"Jordan met Casey"}
		]
	}`}

	generation, err := extract(context.Background(), ai, Source{
		ID: "source-1", ContentSHA256: strings.Repeat("a", 64), Text: prose,
	}, "test-model", 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(ai.messages) != 1 || !strings.Contains(ai.messages[0].Content, prose) {
		t.Fatalf("extraction prompt did not contain the complete prose: %#v", ai.messages)
	}
	for _, instruction := range []string{
		"Extract all explicitly stated factual information",
		"pronouns and possessives",
		"If a reference is ambiguous, do not guess",
	} {
		if !strings.Contains(ai.messages[0].Content, instruction) {
			t.Fatalf("extraction prompt is missing %q", instruction)
		}
	}
	if !ai.options.Reasoning || ai.options.ReasoningBudgetTokens != 256 || ai.options.Temperature != 0.1 || ai.options.TopP != 0.8 || ai.options.JSONSchema == nil {
		t.Fatalf("unexpected extraction options: %#v", ai.options)
	}

	relationships := map[string]string{}
	for _, observation := range generation.Observations {
		if observation.Kind != "relationship-candidate" {
			continue
		}
		payload := observation.Payload.(map[string]any)
		subject := payload["subject"].(map[string]any)["name"].(string)
		object := payload["object"].(map[string]any)["name"].(string)
		relationships[payload["predicate"].(string)] = subject + "->" + object
	}
	for predicate, want := range map[string]string{
		"launched":         "Acme->Beacon",
		"owns":             "Acme->Beacon",
		"headquartered-in": "Acme->Malmö",
		"met":              "Jordan->Casey",
	} {
		if relationships[predicate] != want {
			t.Fatalf("%s relationship = %q, want %q; all relationships: %#v", predicate, relationships[predicate], want, relationships)
		}
	}
	if _, guessed := relationships["emailed"]; guessed {
		t.Fatalf("ambiguous pronoun created a guessed relationship: %#v", relationships)
	}
}

func TestTextChunksUseBoundedOverlap(t *testing.T) {
	words := make([]string, 80)
	for index := range words {
		words[index] = fmt.Sprintf("word%02d", index)
	}
	chunks := textChunks(strings.Join(words, " "), 96, 24)
	if len(chunks) < 2 {
		t.Fatalf("expected multiple chunks, got %#v", chunks)
	}
	for index, chunk := range chunks {
		if len([]byte(chunk)) > 96 {
			t.Fatalf("chunk %d has %d bytes", index, len([]byte(chunk)))
		}
		if index == 0 {
			continue
		}
		previousWords := map[string]bool{}
		for _, word := range strings.Fields(chunks[index-1]) {
			previousWords[word] = true
		}
		shared := false
		for _, word := range strings.Fields(chunk) {
			shared = shared || previousWords[word]
		}
		if !shared {
			t.Fatalf("chunks %d and %d have no overlapping context: %q / %q", index-1, index, chunks[index-1], chunk)
		}
	}
	if got := len(textChunks(strings.Repeat("x", maximumRefinementBytes), maximumChunkBytes, chunkOverlapBytes)); got > maximumRefinementChunks {
		t.Fatalf("maximum-size refinement produced %d chunks, limit is %d", got, maximumRefinementChunks)
	}
}

func TestExtractionStopsAsSoonAsCompleteJSONArrives(t *testing.T) {
	ai := &trailingWhitespaceAI{}
	output, err := extractBatch(context.Background(), ai, "No facts here.", false)
	if err != nil {
		t.Fatal(err)
	}
	if output != `{"entities":[],"claims":[]}` || ai.yielded != 1 {
		t.Fatalf("output=%q yielded=%d", output, ai.yielded)
	}
}
