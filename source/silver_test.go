package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
)

func putSilverBronze(t *testing.T, store *bronzeStore, id, title, mime string, revision int64, content string) bronzeItem {
	t.Helper()
	sum := sha256.Sum256([]byte(content))
	item := bronzeItem{
		ID: id, Revision: revision, Hash: hex.EncodeToString(sum[:]), Title: title, Mime: mime,
		Size: int64(len(content)), Created: 1, Modified: revision,
	}
	if response := bronzeRequest(t, store, http.MethodPut, item, []byte(content)); response.Code != http.StatusOK {
		t.Fatalf("put Bronze: %d: %s", response.Code, response.Body.String())
	}
	return item
}

func attachSilverQueue(service *silverService, bronze *bronzeStore) {
	bronze.onCommit = func(item bronzeItem) error {
		_, err := service.enqueue(item)
		return err
	}
}

type testSemanticModel struct {
	id, revision string
	maximum      int
	run          func(semanticInput) (semanticResult, error)
}

func (m testSemanticModel) identity() (string, string) { return m.id, m.revision }
func (m testSemanticModel) maximumInputBytes() int {
	if m.maximum > 0 {
		return m.maximum
	}
	return 1024 * 1024
}

func (m testSemanticModel) extract(_ context.Context, input semanticInput) (semanticResult, error) {
	return m.run(input)
}

func testConfidence(value float64) *float64 { return &value }

func emptySemanticResult(input semanticInput) semanticResult {
	result := semanticResult{Fragments: make([]semanticFragmentResult, 0, len(input.Fragments))}
	for _, fragment := range input.Fragments {
		result.Fragments = append(result.Fragments, semanticFragmentResult{
			FragmentID: fragment.ID, Entities: []semanticEntityCandidate{},
			Attributes: []semanticAttributeCandidate{}, Relationships: []semanticRelationshipCandidate{},
		})
	}
	return result
}

func namedPeopleTestModel() semanticModel {
	return testSemanticModel{id: "test-people", revision: "1", run: func(input semanticInput) (semanticResult, error) {
		result := emptySemanticResult(input)
		for fragmentIndex, fragment := range input.Fragments {
			var payload struct {
				Text string `json:"text"`
			}
			if err := json.Unmarshal(fragment.Payload, &payload); err != nil {
				return semanticResult{}, err
			}
			for index, label := range []string{"Ada Lovelace", "Grace Hopper"} {
				if strings.Contains(payload.Text, label) {
					result.Fragments[fragmentIndex].Entities = append(result.Fragments[fragmentIndex].Entities,
						semanticEntityCandidate{Ref: fmt.Sprintf("e%d", index+1), Label: label, Confidence: testConfidence(0.99)})
				}
			}
		}
		return result, nil
	}}
}

func TestSilverDataRevisionChangesOnlyWithAuthoritativeSnapshot(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "00000000-0000-4000-8000-000000000001", "queued.txt", "text/plain", 1,
		"Ada Lovelace queued this note.")
	service, err := newSilverServiceWithModel(root+"/silver", bronze, namedPeopleTestModel())
	if err != nil {
		t.Fatal(err)
	}
	if got := service.snapshot().Revision; got != 0 {
		t.Fatalf("queued processing changed authoritative data revision: %d", got)
	}
	if got := service.jobSnapshot().Revision; got == 0 {
		t.Fatal("queued processing did not change job revision")
	}
	if !service.processNext(context.Background()) {
		t.Fatal("Silver worker unexpectedly stopped")
	}
	if snapshot := service.snapshot(); snapshot.Revision != 2 || len(snapshot.Sources) != 1 ||
		snapshot.Sources[0].Representations.Deterministic.State != silverRepresentationReady ||
		snapshot.Sources[0].Representations.Knowledge.State != silverRepresentationReady {
		t.Fatalf("published data did not advance authoritative revision: %+v", snapshot)
	}
}

func TestSilverDataRevisionMigratesFromExistingState(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "silver")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	legacy := `{"schema_version":1,"revision":9,"published":{},"history":{},"entities":{}}`
	if err := os.WriteFile(filepath.Join(dir, "state.json"), []byte(legacy), 0600); err != nil {
		t.Fatal(err)
	}
	service, err := newSilverServiceWithModel(dir, newBronzeStore(filepath.Join(root, "bronze")), nil)
	if err != nil {
		t.Fatal(err)
	}
	if got := service.snapshot().Revision; got != 9 {
		t.Fatalf("migrated data revision: got %d, want 9", got)
	}
	value, err := os.ReadFile(filepath.Join(dir, "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(value), `"data_revision":9`) || !strings.Contains(string(value), `"revision_model":1`) {
		t.Fatalf("revision migration was not persisted: %s", value)
	}
}

func TestSilverQueueAndCheckpointsSurviveRestart(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	item := putSilverBronze(t, bronze, "11111111-1111-4111-8111-111111111111", "people.txt", "text/plain", 1,
		"Ada Lovelace designed a machine.\n\nGrace Hopper built compilers.")
	service, err := newSilverServiceWithConfiguration(root+"/silver", bronze, namedPeopleTestModel(), silverConfiguration{SemanticBatchTargetBytes: 1})
	if err != nil {
		t.Fatal(err)
	}
	if len(service.state.Jobs) != 1 || service.state.Jobs[0].State != "queued" {
		t.Fatalf("Bronze was not durably queued: %+v", service.state.Jobs)
	}

	ctx, cancel := context.WithCancel(context.Background())
	service.afterCheckpoint = func(_ string, batch int) {
		if batch == 0 {
			cancel()
		}
	}
	if service.processNext(ctx) {
		t.Fatal("cancelled processing unexpectedly reported more work")
	}
	if got := service.snapshot(); len(got.Sources) != 1 || len(got.Evidence) != 2 || len(got.Entities) != 0 ||
		got.Sources[0].Representations.Deterministic.State != silverRepresentationReady ||
		got.Sources[0].Representations.Knowledge.State != silverRepresentationProcessing {
		t.Fatalf("deterministic Silver was not independently published before knowledge completed: %+v", got)
	}
	if len(service.state.Jobs[0].Checkpoints) != 1 {
		t.Fatalf("checkpoint was not persisted: %+v", service.state.Jobs[0])
	}

	restarted, err := newSilverServiceWithConfiguration(root+"/silver", bronze, namedPeopleTestModel(), silverConfiguration{SemanticBatchTargetBytes: 1})
	if err != nil {
		t.Fatal(err)
	}
	if restarted.state.Jobs[0].State != "queued" || len(restarted.state.Jobs[0].Checkpoints) != 1 {
		t.Fatalf("restart did not recover queued checkpoint: %+v", restarted.state.Jobs[0])
	}
	restartedSnapshot := restarted.snapshot()
	if len(restartedSnapshot.Sources) != 1 ||
		restartedSnapshot.Sources[0].Representations.Deterministic.State != silverRepresentationReady ||
		restartedSnapshot.Sources[0].Representations.Knowledge.State != silverRepresentationProcessing {
		t.Fatalf("restart did not preserve independent processor states: %+v", restartedSnapshot)
	}
	processed := 0
	restarted.afterCheckpoint = func(_ string, _ int) { processed++ }
	if !restarted.processNext(context.Background()) {
		t.Fatal("restarted worker did not run")
	}
	if processed != 1 {
		t.Fatalf("restart repeated completed batch; processed %d batches", processed)
	}
	snapshot := restarted.snapshot()
	if len(snapshot.Sources) != 1 || snapshot.Sources[0].BronzeContentSHA256 != item.Hash {
		t.Fatalf("complete generation was not published: %+v", snapshot.Sources)
	}
	if len(snapshot.Evidence) != 2 || len(snapshot.Observations) < 4 {
		t.Fatalf("expected evidence-backed extraction and semantics: evidence=%d observations=%d", len(snapshot.Evidence), len(snapshot.Observations))
	}
	if len(snapshot.Entities) != 2 || len(snapshot.Claims) != 2 {
		t.Fatalf("model candidate resolution missing: entities=%d claims=%d", len(snapshot.Entities), len(snapshot.Claims))
	}
}

func TestSilverInterruptedKnowledgeBecomesUnavailableWhenModelIsRemoved(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "13131313-1313-4131-8131-131313131313", "people.txt", "text/plain", 1,
		"Ada Lovelace designed a machine.\n\nGrace Hopper built compilers.")
	firstModel := namedPeopleTestModel().(testSemanticModel)
	service, err := newSilverServiceWithConfiguration(root+"/silver", bronze, firstModel, silverConfiguration{SemanticBatchTargetBytes: 1})
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())
	completed := service.snapshot()
	if len(completed.Sources) != 1 || completed.Sources[0].Representations.Knowledge.State != silverRepresentationReady ||
		len(completed.Entities) == 0 {
		t.Fatalf("initial knowledge did not complete: %+v", completed)
	}

	secondModel := firstModel
	secondModel.revision = "2"
	rebuilding, err := newSilverServiceWithConfiguration(root+"/silver", bronze, secondModel, silverConfiguration{SemanticBatchTargetBytes: 1})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	rebuilding.afterCheckpoint = func(_ string, batch int) {
		if batch == 0 {
			cancel()
		}
	}
	rebuilding.processNext(ctx)
	interrupted := rebuilding.snapshot()
	if len(interrupted.Sources) != 1 ||
		interrupted.Sources[0].Representations.Knowledge.State != silverRepresentationProcessing ||
		interrupted.Sources[0].Coverage.SemanticState != silverSemanticCompleted || len(interrupted.Entities) == 0 {
		t.Fatalf("knowledge was not interrupted in processing: %+v", interrupted)
	}

	restarted, err := newSilverServiceWithConfiguration(root+"/silver", bronze, nil, silverConfiguration{SemanticBatchTargetBytes: 1})
	if err != nil {
		t.Fatal(err)
	}
	if !restarted.processNext(context.Background()) {
		t.Fatal("model-less reconciliation job did not run")
	}
	snapshot := restarted.snapshot()
	if len(snapshot.Sources) != 1 {
		t.Fatalf("source missing after restart: %+v", snapshot)
	}
	source := snapshot.Sources[0]
	if source.Representations.Deterministic.State != silverRepresentationReady ||
		source.Representations.Knowledge.State != silverRepresentationUnavailable ||
		source.Coverage.SemanticState != silverSemanticSkipped ||
		source.Coverage.SemanticSkipReason != silverSkipModelUnavailable ||
		source.ModelID != "" || source.ModelRevision != "" || source.SemanticInputLimit != 0 {
		t.Fatalf("interrupted knowledge was not made unavailable: %+v", source)
	}
	if len(snapshot.Entities) != 0 || len(snapshot.Claims) != 0 {
		t.Fatalf("unavailable knowledge retained semantic output: entities=%d claims=%d", len(snapshot.Entities), len(snapshot.Claims))
	}
	for _, observation := range snapshot.Observations {
		if observation.Producer.ProcessorID != silverExtractionID {
			t.Fatalf("unavailable knowledge retained semantic observation: %+v", observation)
		}
	}
	item, err := bronze.load("13131313-1313-4131-8131-131313131313")
	if err != nil {
		t.Fatal(err)
	}
	restarted.mu.Lock()
	needsReconcile := restarted.needsReconcileLocked(item)
	restarted.mu.Unlock()
	if needsReconcile {
		t.Fatal("model-less unavailable representation was not considered current")
	}
}

func TestSilverCompletedKnowledgeIsPreservedWhenModelIsRemoved(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "14141414-1414-4141-8141-141414141414", "person.txt", "text/plain", 1,
		"Ada Lovelace designed a machine.")
	service, err := newSilverServiceWithModel(root+"/silver", bronze, namedPeopleTestModel())
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())
	before := service.snapshot()
	if len(before.Sources) != 1 || before.Sources[0].Representations.Knowledge.State != silverRepresentationReady ||
		len(before.Entities) == 0 || len(before.Claims) == 0 {
		t.Fatalf("knowledge did not complete before model removal: %+v", before)
	}

	restarted, err := newSilverServiceWithModel(root+"/silver", bronze, nil)
	if err != nil {
		t.Fatal(err)
	}
	after := restarted.snapshot()
	if len(after.Sources) != 1 || after.Sources[0].Representations.Knowledge.State != silverRepresentationReady ||
		after.Sources[0].ModelID == "" || len(after.Entities) != len(before.Entities) || len(after.Claims) != len(before.Claims) {
		t.Fatalf("completed knowledge was not preserved after model removal: before=%+v after=%+v", before, after)
	}
	for _, job := range restarted.state.Jobs {
		if job.State == "queued" || job.State == "running" {
			t.Fatalf("completed knowledge was needlessly requeued without a model: %+v", job)
		}
	}
}

func TestSilverSemanticBatchesPreserveFragmentEvidenceMapping(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	var content strings.Builder
	content.WriteString("name,email\n")
	expectedRows := map[string]int{}
	for index := 0; index < 40; index++ {
		label := fmt.Sprintf("Person %02d", index+1)
		fmt.Fprintf(&content, "%s,person%02d@example.test\n", label, index+1)
		expectedRows[label] = index + 2
	}
	putSilverBronze(t, bronze, "13131313-1313-4131-8131-131313131313", "people.csv", "text/csv", 1, content.String())

	calls := 0
	largestBatch := 0
	model := testSemanticModel{id: "batch-model", revision: "1", run: func(input semanticInput) (semanticResult, error) {
		calls++
		largestBatch = max(largestBatch, len(input.Fragments))
		result := emptySemanticResult(input)
		for index, fragment := range input.Fragments {
			var payload struct {
				Column string `json:"column"`
				Value  string `json:"value"`
			}
			if err := json.Unmarshal(fragment.Payload, &payload); err != nil {
				return semanticResult{}, err
			}
			if fragment.Kind != "parsed-table-cell" {
				return semanticResult{}, fmt.Errorf("unexpected fragment kind: %+v", fragment)
			}
			if payload.Column != "name" {
				continue
			}
			result.Fragments[index].Entities = []semanticEntityCandidate{{
				Ref: "person", Label: payload.Value, Type: "person", Confidence: testConfidence(0.99),
			}}
		}
		return result, nil
	}}
	service, err := newSilverServiceWithModel(root+"/silver", bronze, model)
	if err != nil {
		t.Fatal(err)
	}
	if !service.processNext(context.Background()) {
		t.Fatal("Silver batch job did not run")
	}
	if calls >= len(expectedRows) || largestBatch <= 1 {
		t.Fatalf("semantic fragments were not batched: calls=%d largest_batch=%d", calls, largestBatch)
	}

	snapshot := service.snapshot()
	evidenceByID := map[string]silverEvidence{}
	for _, evidence := range snapshot.Evidence {
		evidenceByID[evidence.ID] = evidence
	}
	candidates := 0
	for _, observation := range snapshot.Observations {
		if observation.Kind != "entity-candidate" {
			continue
		}
		candidates++
		if len(observation.EvidenceIDs) != 1 {
			t.Fatalf("batched result lost its single-fragment evidence: %+v", observation)
		}
		var payload struct {
			Label string `json:"label"`
		}
		if err := json.Unmarshal(observation.Payload, &payload); err != nil {
			t.Fatal(err)
		}
		evidence, ok := evidenceByID[observation.EvidenceIDs[0]]
		row, rowOK := evidence.Selector["row"].(int)
		if !ok || !rowOK || row != expectedRows[payload.Label] {
			t.Fatalf("semantic result mapped to the wrong CSV row: label=%q evidence=%+v", payload.Label, evidence)
		}
	}
	if candidates != len(expectedRows) || len(snapshot.Entities) != len(expectedRows) {
		t.Fatalf("batched candidates missing: candidates=%d entities=%d", candidates, len(snapshot.Entities))
	}
}

func TestSilverSemanticBatchBoundariesUseEncodedInputSize(t *testing.T) {
	job := silverJob{BronzeSourceID: "source", BronzeContentSHA256: "hash", Title: "note.txt", Mime: "text/plain"}
	fragment := func(text string, start int) parsedSilverFragment {
		return fragmentWithTextRange("text-block", start, start+len(text), text, map[string]any{"text": text})
	}
	fragments := []parsedSilverFragment{
		fragment(strings.Repeat("a", 20), 0),
		fragment(strings.Repeat("b", 20), 20),
		fragment(strings.Repeat("c", 600), 40),
		fragment(strings.Repeat("d", 20), 640),
	}
	twoSmall, err := semanticInputForFragments(job, fragments[:2])
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(twoSmall)
	if err != nil {
		t.Fatal(err)
	}
	batches, err := buildSilverBatches(job, fragments, len(encoded), nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(batches) != 3 || len(batches[0].Fragments) != 2 || len(batches[1].Fragments) != 1 || len(batches[2].Fragments) != 1 {
		t.Fatalf("batch boundaries did not follow encoded semantic size: %+v", batches)
	}
}

func TestSilverSemanticBatchBoundariesRespectModelInputLimit(t *testing.T) {
	job := silverJob{BronzeSourceID: "source", BronzeContentSHA256: "hash", Title: "note.txt", Mime: "text/plain"}
	first := fragmentWithTextRange("text-block", 0, 10, "fragment-a", map[string]any{"text": "fragment-a"})
	second := fragmentWithTextRange("text-block", 1, 11, "fragment-b", map[string]any{"text": "fragment-b"})
	single, err := semanticInputForFragments(job, []parsedSilverFragment{first})
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(single)
	if err != nil {
		t.Fatal(err)
	}
	model := testSemanticModel{id: "limited", revision: "1", maximum: len(encoded), run: func(input semanticInput) (semanticResult, error) {
		return emptySemanticResult(input), nil
	}}
	batches, err := buildSilverBatches(job, []parsedSilverFragment{first, second}, 1024*1024, model)
	if err != nil {
		t.Fatal(err)
	}
	if len(batches) != 2 || len(batches[0].Fragments) != 1 || len(batches[1].Fragments) != 1 {
		t.Fatalf("model input limit did not cap the configured batch target: %+v", batches)
	}
}

func TestSilverOversizedSemanticFragmentKeepsDeterministicExtraction(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	content := fmt.Sprintf(`{"long":%q,"short":"Ada"}`, strings.Repeat("x", 2048))
	putSilverBronze(t, bronze, "15151515-1515-4151-8151-151515151515", "values.json", "application/json", 1, content)

	modelCalls := 0
	model := testSemanticModel{id: "limited-model", revision: "1", maximum: 512, run: func(input semanticInput) (semanticResult, error) {
		modelCalls++
		if len(input.Fragments) != 1 || input.Fragments[0].Selector["pointer"] != "/short" {
			return semanticResult{}, fmt.Errorf("oversized fragment was sent to the model: %+v", input.Fragments)
		}
		return emptySemanticResult(input), nil
	}}
	service, err := newSilverServiceWithModel(root+"/silver", bronze, model)
	if err != nil {
		t.Fatal(err)
	}
	if !service.processNext(context.Background()) {
		t.Fatal("Silver job did not run")
	}
	job := service.state.Jobs[0]
	if job.State != "completed" || job.Attempts != 0 || modelCalls != 1 {
		t.Fatalf("oversized fragment caused a retry or blocked later semantics: job=%+v calls=%d", job, modelCalls)
	}
	snapshot := service.snapshot()
	if len(snapshot.Sources) != 1 || len(snapshot.Evidence) != 2 || len(snapshot.Observations) != 2 {
		t.Fatalf("deterministic Silver was not published for every fragment: %+v", snapshot)
	}
	coverage := snapshot.Sources[0].Coverage
	if coverage.ExtractionState != silverExtractionCompleted || coverage.SemanticState != silverSemanticPartial ||
		coverage.SemanticSkipReason != silverSkipFragmentTooLarge {
		t.Fatalf("oversized fragment coverage was reported as complete: %+v", coverage)
	}
}

func TestSilverOversizedTextPublishesExplicitSkippedCoverage(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	content := strings.Repeat("x", silverMaximumInputBytes+1)
	item := putSilverBronze(t, bronze, "16161616-1616-4161-8161-161616161616", "oversized.txt", "text/plain", 1, content)
	modelCalls := 0
	model := testSemanticModel{id: "model", revision: "1", run: func(input semanticInput) (semanticResult, error) {
		modelCalls++
		return emptySemanticResult(input), nil
	}}
	service, err := newSilverServiceWithModel(root+"/silver", bronze, model)
	if err != nil {
		t.Fatal(err)
	}
	if !service.processNext(context.Background()) {
		t.Fatal("Silver oversized-source job did not run")
	}
	snapshot := service.snapshot()
	if len(snapshot.Sources) != 1 || modelCalls != 0 {
		t.Fatalf("oversized source was not published without semantic inference: sources=%+v calls=%d", snapshot.Sources, modelCalls)
	}
	coverage := snapshot.Sources[0].Coverage
	if coverage.ExtractionState != silverExtractionSkipped || coverage.SemanticState != silverSemanticSkipped ||
		coverage.SemanticSkipReason != silverSkipSourceTooLarge {
		t.Fatalf("oversized source coverage: %+v", coverage)
	}
	service.mu.Lock()
	needed := service.needsReconcileLocked(item)
	service.mu.Unlock()
	if needed {
		t.Fatal("unsupported deterministic input was needlessly requeued for knowledge")
	}
}

func TestSilverInputLimitChangeRequeuesPartialSemanticCoverage(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	content := fmt.Sprintf(`{"long":%q,"short":"Ada"}`, strings.Repeat("x", 2048))
	putSilverBronze(t, bronze, "17171717-1717-4171-8171-171717171717", "values.json", "application/json", 1, content)
	result := func(input semanticInput) (semanticResult, error) { return emptySemanticResult(input), nil }
	limited, err := newSilverServiceWithModel(root+"/silver", bronze,
		testSemanticModel{id: "same-model", revision: "1", maximum: 512, run: result})
	if err != nil {
		t.Fatal(err)
	}
	limited.processNext(context.Background())
	if got := limited.snapshot().Sources[0].Coverage.SemanticState; got != silverSemanticPartial {
		t.Fatalf("limited model coverage = %q, want partial", got)
	}

	expanded, err := newSilverServiceWithModel(root+"/silver", bronze,
		testSemanticModel{id: "same-model", revision: "1", maximum: 8192, run: result})
	if err != nil {
		t.Fatal(err)
	}
	before := expanded.snapshot()
	if len(before.Sources) != 1 || before.Sources[0].Stale || len(before.Processing) != 1 ||
		before.Processing[0].Representation != "knowledge" {
		t.Fatalf("input-limit change did not queue replacement work: %+v", before)
	}
	expanded.processNext(context.Background())
	after := expanded.snapshot()
	if len(after.Sources) != 1 || after.Sources[0].Stale ||
		after.Sources[0].Coverage.SemanticState != silverSemanticCompleted {
		t.Fatalf("expanded model did not complete semantic coverage: %+v", after.Sources)
	}
}

func TestSilverSemanticBatchRetryKeepsCompletedCheckpoints(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "14141414-1414-4141-8141-141414141414", "retry.txt", "text/plain", 1,
		"First fragment.\n\nSecond fragment.\n\nThird fragment.")
	calls := map[string]int{}
	model := testSemanticModel{id: "retry-model", revision: "1", run: func(input semanticInput) (semanticResult, error) {
		var payload struct {
			Text string `json:"text"`
		}
		if len(input.Fragments) != 1 {
			return semanticResult{}, fmt.Errorf("expected one fragment per forced test batch, got %d", len(input.Fragments))
		}
		if err := json.Unmarshal(input.Fragments[0].Payload, &payload); err != nil {
			return semanticResult{}, err
		}
		calls[payload.Text]++
		if payload.Text == "Second fragment." && calls[payload.Text] == 1 {
			return semanticResult{}, errors.New("transient inference failure")
		}
		return emptySemanticResult(input), nil
	}}
	service, err := newSilverServiceWithConfiguration(root+"/silver", bronze, model,
		silverConfiguration{SemanticBatchTargetBytes: 1})
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())
	job := &service.state.Jobs[0]
	if job.State != "failed" || len(job.Checkpoints) != 1 {
		t.Fatalf("failure did not retain the completed batch: %+v", job)
	}
	job.RetryAt = time.Now().Add(-time.Second).UnixMilli()
	if err := service.reconcile(); err != nil {
		t.Fatal(err)
	}
	if !service.processNext(context.Background()) {
		t.Fatal("retry did not run")
	}
	if calls["First fragment."] != 1 || calls["Second fragment."] != 2 || calls["Third fragment."] != 1 {
		t.Fatalf("retry repeated completed semantic work: %+v", calls)
	}
	if len(service.snapshot().Sources) != 1 {
		t.Fatal("successful retry was not published")
	}
}

func TestSilverSemanticContractFailureRetriesBeforeSplitting(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "18181818-1818-4181-8181-181818181818", "retry-contract.txt", "text/plain", 1,
		"First fragment.\n\nSecond fragment.")
	calls := 0
	model := testSemanticModel{id: "contract-retry-model", revision: "1", run: func(input semanticInput) (semanticResult, error) {
		calls++
		if calls < semanticContractMaximumAttempts {
			result := emptySemanticResult(input)
			result.Fragments = result.Fragments[:1]
			return result, nil
		}
		return emptySemanticResult(input), nil
	}}
	service, err := newSilverServiceWithModel(root+"/silver", bronze, model)
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())
	if calls != semanticContractMaximumAttempts || service.state.Jobs[0].State != "completed" {
		t.Fatalf("contract failure was not retried in place: calls=%d job=%+v", calls, service.state.Jobs[0])
	}
	coverage := service.snapshot().Sources[0].Coverage
	if coverage.SemanticState != silverSemanticCompleted || coverage.SemanticSkipReason != "" {
		t.Fatalf("successful contract retry lost complete coverage: %+v", coverage)
	}
}

func TestSilverSemanticContractFailureSplitsAndSourceOwnsSingleIdentity(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "28282828-2828-4282-8282-282828282828", "split-contract.txt", "text/plain", 1,
		"Ada One.\n\nGrace Two.\n\nKatherine Three.\n\nDorothy Four.")
	callsBySize := map[int]int{}
	model := testSemanticModel{id: "contract-split-model", revision: "1", run: func(input semanticInput) (semanticResult, error) {
		callsBySize[len(input.Fragments)]++
		if len(input.Fragments) > 1 {
			result := emptySemanticResult(input)
			result.Fragments = result.Fragments[:len(result.Fragments)-1]
			return result, nil
		}
		var payload struct {
			Text string `json:"text"`
		}
		if err := json.Unmarshal(input.Fragments[0].Payload, &payload); err != nil {
			return semanticResult{}, err
		}
		result := emptySemanticResult(input)
		result.Fragments[0].FragmentID = "model-owned-id"
		result.Fragments[0].Entities = []semanticEntityCandidate{{
			Ref: "person", Label: strings.TrimSuffix(payload.Text, "."), Type: "person", Confidence: testConfidence(0.99),
		}}
		return result, nil
	}}
	service, err := newSilverServiceWithModel(root+"/silver", bronze, model)
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())
	if callsBySize[4] != semanticContractMaximumAttempts || callsBySize[2] != 2*semanticContractMaximumAttempts || callsBySize[1] != 4 {
		t.Fatalf("contract recovery did not recursively split as expected: %+v", callsBySize)
	}
	snapshot := service.snapshot()
	if len(snapshot.Sources) != 1 || len(snapshot.Entities) != 4 ||
		snapshot.Sources[0].Coverage.SemanticState != silverSemanticCompleted {
		t.Fatalf("split contract recovery did not publish complete semantics: %+v", snapshot)
	}
	for _, evidence := range snapshot.Evidence {
		if evidence.BronzeSourceID != "28282828-2828-4282-8282-282828282828" {
			t.Fatalf("split recovery changed Source-owned provenance: %+v", evidence)
		}
	}
}

func TestSilverSemanticContractRecoverySharesCandidateBudgetAcrossSplits(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "38383838-3838-4383-8383-383838383838", "bounded-contract.txt", "text/plain", 1,
		"First fragment.\n\nSecond fragment.")
	calls := map[string]int{}
	model := testSemanticModel{id: "bounded-contract-model", revision: "1", run: func(input semanticInput) (semanticResult, error) {
		if len(input.Fragments) > 1 {
			calls["combined"]++
			result := emptySemanticResult(input)
			result.Fragments = result.Fragments[:1]
			return result, nil
		}
		var payload struct {
			Text string `json:"text"`
		}
		if err := json.Unmarshal(input.Fragments[0].Payload, &payload); err != nil {
			return semanticResult{}, err
		}
		calls[payload.Text]++
		result := emptySemanticResult(input)
		for index := 0; index < semanticMaximumCandidates; index++ {
			result.Fragments[0].Entities = append(result.Fragments[0].Entities, semanticEntityCandidate{
				Ref: fmt.Sprintf("e%d", index), Label: fmt.Sprintf("Candidate %d", index), Confidence: testConfidence(0.99),
			})
		}
		return result, nil
	}}
	service, err := newSilverServiceWithModel(root+"/silver", bronze, model)
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())
	if calls["combined"] != semanticContractMaximumAttempts || calls["First fragment."] != 1 ||
		calls["Second fragment."] != semanticContractMaximumAttempts {
		t.Fatalf("shared recovery budget produced unexpected calls: %+v", calls)
	}
	snapshot := service.snapshot()
	if len(snapshot.Entities) != semanticMaximumCandidates {
		t.Fatalf("split recovery exceeded its aggregate candidate bound: entities=%d", len(snapshot.Entities))
	}
	coverage := snapshot.Sources[0].Coverage
	if coverage.SemanticState != silverSemanticPartial || coverage.SemanticSkipReason != silverSkipModelContract {
		t.Fatalf("candidate budget exhaustion did not preserve partial coverage: %+v", coverage)
	}
}

func TestSilverSemanticContractFailureSkipsOnlyIrrecoverableFragment(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "29292929-2929-4292-8292-292929292929", "partial-contract.txt", "text/plain", 1,
		"Good fragment.\n\nBad fragment.")
	calls := map[string]int{}
	model := testSemanticModel{id: "contract-partial-model", revision: "1", run: func(input semanticInput) (semanticResult, error) {
		if len(input.Fragments) > 1 {
			calls["combined"]++
			result := emptySemanticResult(input)
			result.Fragments = result.Fragments[:1]
			return result, nil
		}
		var payload struct {
			Text string `json:"text"`
		}
		if err := json.Unmarshal(input.Fragments[0].Payload, &payload); err != nil {
			return semanticResult{}, err
		}
		calls[payload.Text]++
		if payload.Text == "Bad fragment." {
			return semanticResult{Fragments: []semanticFragmentResult{}}, nil
		}
		return emptySemanticResult(input), nil
	}}
	service, err := newSilverServiceWithModel(root+"/silver", bronze, model)
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())
	if calls["combined"] != semanticContractMaximumAttempts || calls["Good fragment."] != 1 ||
		calls["Bad fragment."] != semanticContractMaximumAttempts {
		t.Fatalf("unexpected partial recovery calls: %+v", calls)
	}
	job := service.state.Jobs[0]
	snapshot := service.snapshot()
	if job.State != "completed" || job.Attempts != 0 || len(snapshot.Processing) != 0 || len(snapshot.Sources) != 1 {
		t.Fatalf("isolated contract failure blocked publication: job=%+v snapshot=%+v", job, snapshot)
	}
	coverage := snapshot.Sources[0].Coverage
	if coverage.SemanticState != silverSemanticPartial || coverage.SemanticSkipReason != silverSkipModelContract {
		t.Fatalf("omitted fragment was mistaken for successful empty semantics: %+v", coverage)
	}
	if len(snapshot.Evidence) != 2 || len(snapshot.Observations) != 2 {
		t.Fatalf("partial semantics lost deterministic extraction: evidence=%d observations=%d", len(snapshot.Evidence), len(snapshot.Observations))
	}
}

func TestSilverSingleContractFailurePublishesSkippedCoverage(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "30303030-3030-4303-8303-303030303030", "invalid.txt", "text/plain", 1, "A note")
	calls := 0
	model := testSemanticModel{id: "invalid-model", revision: "1", run: func(semanticInput) (semanticResult, error) {
		calls++
		return semanticResult{Fragments: []semanticFragmentResult{}}, nil
	}}
	service, err := newSilverServiceWithModel(root+"/silver", bronze, model)
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())
	job := service.state.Jobs[0]
	if job.State != "completed" || job.Attempts != 0 || calls != semanticContractMaximumAttempts {
		t.Fatalf("single contract failure did not complete with bounded attempts: calls=%d job=%+v", calls, job)
	}
	coverage := service.snapshot().Sources[0].Coverage
	if coverage.SemanticState != silverSemanticSkipped || coverage.SemanticSkipReason != silverSkipModelContract {
		t.Fatalf("single contract failure did not publish skipped coverage: %+v", coverage)
	}
}

func TestSilverFailedFailureStatePersistenceRemainsObservable(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "19191919-1919-4191-8191-191919191919", "failure.txt", "text/plain", 1, "A note")
	blocker := filepath.Join(root, "blocker")
	if err := os.WriteFile(blocker, nil, 0600); err != nil {
		t.Fatal(err)
	}
	var service *silverService
	model := testSemanticModel{id: "failing-model", revision: "1", run: func(semanticInput) (semanticResult, error) {
		service.path = filepath.Join(blocker, "state.json")
		return semanticResult{}, errors.New("model unavailable")
	}}
	var err error
	service, err = newSilverServiceWithModel(root+"/silver", bronze, model)
	if err != nil {
		t.Fatal(err)
	}
	originalPath := service.path
	service.processNext(context.Background())
	job := service.state.Jobs[0]
	if job.State != "failed" || !service.pendingPersistence || service.snapshot().Error == "" {
		t.Fatalf("failed-state storage error was hidden or left running: job=%+v error=%q", job, service.snapshot().Error)
	}
	service.state.Jobs[0].RetryAt = time.Now().Add(time.Hour).UnixMilli()
	service.path = originalPath
	if err := service.reconcile(); err != nil {
		t.Fatal(err)
	}
	restarted, err := newSilverServiceWithModel(root+"/silver", bronze, model)
	if err != nil {
		t.Fatal(err)
	}
	if restarted.state.Jobs[0].State != "failed" || !restarted.state.Jobs[0].Retryable {
		t.Fatalf("failed state was not durably recovered: %+v", restarted.state.Jobs[0])
	}
}

func TestSilverPendingFailurePersistenceBlocksLaterJobs(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "20202020-2020-4202-8202-202020202020", "first.txt", "text/plain", 1, "First note")
	putSilverBronze(t, bronze, "21212121-2121-4212-8212-212121212121", "second.txt", "text/plain", 1, "Second note")
	blocker := filepath.Join(root, "blocker")
	if err := os.WriteFile(blocker, nil, 0600); err != nil {
		t.Fatal(err)
	}
	var service *silverService
	calls := 0
	model := testSemanticModel{id: "failure-then-success", revision: "1", run: func(input semanticInput) (semanticResult, error) {
		calls++
		if calls == 1 {
			service.path = filepath.Join(blocker, "state.json")
			return semanticResult{}, errors.New("model unavailable")
		}
		return emptySemanticResult(input), nil
	}}
	var err error
	service, err = newSilverServiceWithModel(root+"/silver", bronze, model)
	if err != nil {
		t.Fatal(err)
	}
	originalPath := service.path
	if !service.processNext(context.Background()) {
		t.Fatal("first job was not processed")
	}
	if service.state.Jobs[0].State != "failed" || !service.pendingPersistence {
		t.Fatalf("first failure was not retained pending persistence: %+v", service.state.Jobs)
	}
	if service.processNext(context.Background()) {
		t.Fatal("worker selected later work while failed state was not durable")
	}
	if service.state.Jobs[1].State != "queued" || calls != 1 {
		t.Fatalf("later job advanced before storage recovered: jobs=%+v calls=%d", service.state.Jobs, calls)
	}

	service.path = originalPath
	if err := service.reconcile(); err != nil {
		t.Fatal(err)
	}
	if !service.processNext(context.Background()) {
		t.Fatal("later job did not run after storage recovered")
	}
	if service.state.Jobs[1].State != "completed" || calls != 2 {
		t.Fatalf("later job did not complete after recovery: jobs=%+v calls=%d", service.state.Jobs, calls)
	}
}

func TestSilverReconciliationFailureIsObservable(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	service, err := newSilverServiceWithModel(root+"/silver", bronze, nil)
	if err != nil {
		t.Fatal(err)
	}
	originalDir := bronze.dir
	blocker := filepath.Join(root, "bronze-blocker")
	if err := os.WriteFile(blocker, nil, 0600); err != nil {
		t.Fatal(err)
	}
	bronze.dir = blocker
	if err := service.reconcile(); err == nil {
		t.Fatal("reconciliation unexpectedly succeeded")
	}
	if got := service.snapshot().Error; !strings.Contains(got, "reconciliation failed") {
		t.Fatalf("reconciliation error was not exposed: %q", got)
	}
	_, _, _, statusError := service.refreshStatus()
	if !strings.Contains(statusError, "reconciliation failed") {
		t.Fatalf("lightweight status omitted reconciliation error: %q", statusError)
	}
	bronze.dir = originalDir
	if err := service.reconcile(); err != nil {
		t.Fatal(err)
	}
	if got := service.snapshot().Error; got != "" {
		t.Fatalf("successful reconciliation did not clear operational error: %q", got)
	}
}

func TestSilverFailedSaveDoesNotExposeUnpublishedGeneration(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(filepath.Join(root, "bronze"))
	putSilverBronze(t, bronze, "77777777-7777-4777-8777-777777777777", "note.txt", "text/plain", 1, "Ada Lovelace wrote notes.")
	service, err := newSilverServiceWithModel(filepath.Join(root, "silver"), bronze, namedPeopleTestModel())
	if err != nil {
		t.Fatal(err)
	}
	originalPath := service.path
	blocker := filepath.Join(root, "blocker")
	if err := os.WriteFile(blocker, nil, 0600); err != nil {
		t.Fatal(err)
	}
	service.afterCheckpoint = func(_ string, _ int) {
		service.path = filepath.Join(blocker, "state.json")
	}
	service.processNext(context.Background())
	if snapshot := service.snapshot(); len(snapshot.Sources) != 1 || len(snapshot.Evidence) != 1 ||
		len(snapshot.Entities) != 0 || snapshot.Sources[0].Representations.Deterministic.State != silverRepresentationReady {
		t.Fatalf("failed knowledge publication damaged deterministic Silver: %+v", snapshot)
	}
	service.path = originalPath
	restarted, err := newSilverServiceWithModel(filepath.Join(root, "silver"), bronze, namedPeopleTestModel())
	if err != nil {
		t.Fatal(err)
	}
	if len(restarted.state.Jobs) != 1 || len(restarted.state.Jobs[0].Checkpoints) != 1 {
		t.Fatalf("failed publication lost the durable checkpoint: %+v", restarted.state.Jobs)
	}
	if !restarted.processNext(context.Background()) || len(restarted.snapshot().Sources) != 1 {
		t.Fatal("failed publication did not resume from checkpoint")
	}
}

func TestSilverKeepsPreviousProcessorGenerationVisibleAsStale(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(filepath.Join(root, "bronze"))
	item := putSilverBronze(t, bronze, "12121212-1212-4121-8121-121212121212", "note.txt", "text/plain", 1, "Ada Lovelace wrote notes.")
	service, err := newSilverService(filepath.Join(root, "silver"), bronze)
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())

	prior := service.state.Published[item.ID]
	prior.Source.ProcessorVersion = "1"
	service.state.Published[item.ID] = prior
	service.state.Jobs[0].ProcessorVersion = "1"
	if _, err := service.enqueue(item); err != nil {
		t.Fatal(err)
	}
	snapshot := service.snapshot()
	if len(snapshot.Sources) != 1 || !snapshot.Sources[0].Stale || len(snapshot.Processing) != 1 {
		t.Fatalf("previous generation was not exposed as stale during replacement: %+v", snapshot)
	}
	if !service.processNext(context.Background()) {
		t.Fatal("replacement generation did not run")
	}
	snapshot = service.snapshot()
	if len(snapshot.Sources) != 1 || snapshot.Sources[0].Stale || len(snapshot.Processing) != 0 ||
		snapshot.Sources[0].ProcessorVersion != silverProcessorVersion {
		t.Fatalf("replacement generation did not become current: %+v", snapshot)
	}
}

func TestSilverReconcilesCommitAfterImmediateEnqueueFailure(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(filepath.Join(root, "bronze"))
	service, err := newSilverService(filepath.Join(root, "silver"), bronze)
	if err != nil {
		t.Fatal(err)
	}
	attachSilverQueue(service, bronze)
	originalPath := service.path
	blocker := filepath.Join(root, "blocker")
	if err := os.WriteFile(blocker, nil, 0600); err != nil {
		t.Fatal(err)
	}
	service.path = filepath.Join(blocker, "state.json")
	content := "Ada Lovelace survived a queue outage."
	sum := sha256.Sum256([]byte(content))
	item := bronzeItem{ID: "99999999-9999-4999-8999-999999999999", Revision: 1,
		Hash: hex.EncodeToString(sum[:]), Title: "outage.txt", Mime: "text/plain",
		Size: int64(len(content)), Created: 1, Modified: 1}
	response := bronzeRequest(t, bronze, http.MethodPut, item, []byte(content))
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("enqueue failure status: %d", response.Code)
	}
	if _, err := bronze.load(item.ID); err != nil {
		t.Fatalf("Bronze was not persisted before enqueue failed: %v", err)
	}
	service.path = originalPath
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	service.start(ctx)
	t.Cleanup(service.wait)
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if snapshot := service.snapshot(); len(snapshot.Sources) == 1 && snapshot.Sources[0].BronzeSourceID == item.ID {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("persisted Bronze was not reconciled into Silver without a restart")
}

func TestSilverProcessingIdentityIncludesFormatMetadata(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	item := putSilverBronze(t, bronze, "66666666-6666-4666-8666-666666666666", "data.txt", "text/plain", 1, `{"name":"Ada Lovelace"}`)
	service, err := newSilverServiceWithModel(root+"/silver", bronze, namedPeopleTestModel())
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())
	item.Title = "data.json"
	item.Mime = "application/json"
	changed, err := service.enqueue(item)
	if err != nil || !changed || len(service.state.Jobs) != 2 || service.state.Jobs[1].State != "queued" {
		t.Fatalf("format metadata did not produce distinct work: changed=%v err=%v jobs=%+v", changed, err, service.state.Jobs)
	}
	if service.state.Jobs[0].Title == service.state.Jobs[1].Title || service.state.Jobs[0].Mime == service.state.Jobs[1].Mime {
		t.Fatalf("format identity was not captured in jobs: %+v", service.state.Jobs)
	}
}

func TestSilverFormatAwareParsingAndGenericFallback(t *testing.T) {
	tests := []struct {
		name, title, mime, content, kind string
	}{
		{"json", "data", "application/json; charset=utf-8", `{"person":{"name":"Ada Lovelace"}}`, "parsed-json-value"},
		{"csv", "data.csv", "text/csv", "name,email\nAda Lovelace,ada@example.test\n", "parsed-table-row"},
		{"markdown", "note.md", "text/markdown", "# Project Notes\n\nAda Lovelace drafted this.", "markdown-heading"},
		{"unknown", "archive.odd", "application/octet-stream", "Ada Lovelace left readable text.", "text-block"},
		{"malformed csv fallback", "broken.csv", "text/csv", "name\n\"unterminated", "text-block"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			item := bronzeItem{Title: test.title, Mime: test.mime}
			fragments, supported, err := parseSilverText(item, []byte(test.content))
			if err != nil || !supported {
				t.Fatalf("parse failed: supported=%v err=%v", supported, err)
			}
			found := false
			for _, fragment := range fragments {
				if fragment.Kind == test.kind {
					found = true
				}
			}
			if !found {
				t.Fatalf("missing %s in %+v", test.kind, fragments)
			}
		})
	}
}

func TestSilverCSVStandardLibraryFixtures(t *testing.T) {
	tests := []struct {
		name        string
		content     string
		want        []string
		wantLine    int
		wantColumns int
	}{
		{"ordinary", "name,city\nAda,London\n", []string{"Ada", "London"}, 2, 2},
		{"quoted comma", "name,note\n\"Lovelace, Ada\",\"engine, analytical\"\n", []string{"Lovelace, Ada", "engine, analytical"}, 2, 2},
		{"quoted multiline and empty", "name,note,empty\r\nJonas,\"first line\r\nsecond line\",\r\n", []string{"Jonas", "first line\nsecond line", ""}, 2, 3},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			records, ok := parseCSVRecords(test.content)
			if !ok || len(records) != 2 {
				t.Fatalf("parseCSVRecords: ok=%v records=%+v", ok, records)
			}
			got := records[1]
			if strings.Join(got.Values, "|") != strings.Join(test.want, "|") ||
				got.SourceLine != test.wantLine || len(got.Positions) != test.wantColumns {
				t.Fatalf("record=%+v want values=%q line=%d columns=%d", got, test.want, test.wantLine, test.wantColumns)
			}
			fragments, ok := parseCSVFragments(test.content)
			if !ok || len(fragments) < 2 || fragments[1].Kind != "parsed-table-row" ||
				fragments[1].Selector["source_line"] != test.wantLine {
				t.Fatalf("CSV fragments lost logical or physical provenance: %+v", fragments)
			}
		})
	}
	if _, ok := parseCSVRecords("name,note\nAda,\"unterminated"); ok {
		t.Fatal("malformed CSV was accepted")
	}
}

func TestSilverCSVCompoundCellBecomesAtomicEvidenceWithRecordContext(t *testing.T) {
	content := "Name,Notes\nJonas Sandvall,\"Birthday: 1986-08-27\nNotes: 2543\nPhone 1 - Value: 073-512 61 77\nAddress 1 - City: Stockholm\nAddress 1 - State: Stockholms Lan\nAddress 1 - Country: Sweden\"\n"
	fragments, supported, err := parseSilverText(bronzeItem{Title: "contacts.csv", Mime: "text/csv"}, []byte(content))
	if err != nil || !supported {
		t.Fatalf("parse: supported=%v err=%v", supported, err)
	}
	keys := []string{}
	for _, fragment := range fragments {
		if fragment.Kind != "parsed-key-value" {
			continue
		}
		if fragment.Selector["row"] != 2 || fragment.Selector["column"] != "Notes" ||
			fragment.Selector["child"] == nil || fragment.Context == nil {
			t.Fatalf("compound child lost cell provenance or record context: %+v", fragment)
		}
		var payload struct {
			Key   string `json:"key"`
			Value string `json:"value"`
		}
		encoded, _ := json.Marshal(fragment.Payload)
		if err := json.Unmarshal(encoded, &payload); err != nil || payload.Value == "" {
			t.Fatalf("invalid atomic payload: %s: %v", encoded, err)
		}
		keys = append(keys, payload.Key)
	}
	wantKeys := []string{"Birthday", "Notes", "Phone 1 - Value", "Address 1 - City", "Address 1 - State", "Address 1 - Country"}
	if strings.Join(keys, "|") != strings.Join(wantKeys, "|") {
		t.Fatalf("compound cell was not atomized: got=%q want=%q fragments=%+v", keys, wantKeys, fragments)
	}

	job := silverJob{BronzeSourceID: "source", BronzeContentSHA256: hashBytes([]byte(content)), Title: "contacts.csv", Mime: "text/csv"}
	input, err := semanticInputForFragments(job, fragments)
	if err != nil {
		t.Fatal(err)
	}
	if len(input.Fragments) != 1+len(wantKeys) || len(input.Contexts) != 1 {
		t.Fatalf("semantic units/context: fragments=%d contexts=%d input=%+v", len(input.Fragments), len(input.Contexts), input)
	}
	if !strings.Contains(string(input.Contexts[0].Payload), "Jonas Sandvall") ||
		strings.Contains(string(input.Contexts[0].Payload), "Birthday") {
		t.Fatalf("record context did not isolate the compound source field: %s", input.Contexts[0].Payload)
	}
	for _, fragment := range input.Fragments {
		if fragment.Kind == "parsed-table-row" || strings.Contains(string(fragment.Payload), "Phone 1 - Value: 073") {
			t.Fatalf("compound row/cell leaked into semantic local evidence: %+v", fragment)
		}
	}
}

func TestSilverRecursivelyDecomposesStructuredJSONStrings(t *testing.T) {
	content := `{"name":"Jonas Sandvall","notes":"Birthday: 1986-08-27\nCity: Stockholm","metadata":"{\"country\":\"Sweden\",\"active\":true}","prose":"Jonas likes long walks."}`
	fragments, ok := parseJSONFragments(content)
	if !ok {
		t.Fatal("valid JSON was rejected")
	}
	found := map[string]bool{}
	for _, fragment := range fragments {
		switch fragment.Kind {
		case "parsed-key-value":
			var payload map[string]any
			encoded, _ := json.Marshal(fragment.Payload)
			_ = json.Unmarshal(encoded, &payload)
			found[fmt.Sprint(payload["key"])] = true
		case "parsed-json-value", "parsed-embedded-json-value":
			if fragment.Selector["pointer"] == "/prose" && !fragment.StructuralOnly {
				found["prose"] = true
			}
			if structure, ok := fragment.Selector["structure"].(map[string]any); ok && structure["pointer"] == "/country" {
				found["country"] = true
			}
		}
	}
	for _, key := range []string{"Birthday", "City", "country", "prose"} {
		if !found[key] {
			t.Fatalf("missing recursively parsed unit %q: %+v", key, fragments)
		}
	}
}

func TestSilverKeyValueTextUsesExactRangesAndLeavesProseAlone(t *testing.T) {
	content := "  Birthday: 1986-08-27\nCity: Stockholm\nCountry: Sweden  "
	fragments := parseGenericFragments(content)
	if len(fragments) != 4 || !fragments[0].StructuralOnly {
		t.Fatalf("key/value text was not decomposed conservatively: %+v", fragments)
	}
	for _, fragment := range fragments[1:] {
		start := fragment.Selector["start_byte"].(int)
		end := fragment.Selector["end_byte"].(int)
		if content[start:end] != fragment.Text || fragment.Kind != "parsed-key-value" {
			t.Fatalf("atomic text range %d:%d selected %q for %+v", start, end, content[start:end], fragment)
		}
	}
	prose := parseGenericFragments("Observation: Jonas arrived yesterday and then described the entire trip in prose.")
	if len(prose) != 1 || prose[0].Kind != "text-block" || prose[0].StructuralOnly {
		t.Fatalf("ordinary prose was over-decomposed: %+v", prose)
	}
}

func TestSilverLongStructuredChildrenKeepPayloadAndSelector(t *testing.T) {
	value := strings.Repeat("å", silverMaximumBatchBytes)
	content := fmt.Sprintf(`{"value":%q}`, value)
	fragments := parseGenericFragments(content)
	structured := []parsedSilverFragment{}
	for _, fragment := range fragments {
		if fragment.Kind == "parsed-embedded-json-value" {
			structured = append(structured, fragment)
		}
	}
	if len(structured) != 1 {
		t.Fatalf("long structured child was split or lost: %+v", fragments)
	}
	child := structured[0]
	if child.Selector["kind"] != "text-block-child" || child.Selector["start_byte"] != 0 ||
		child.Selector["end_byte"] != len(content) || child.Text != value {
		t.Fatalf("long structured child lost parent-range provenance: %+v", child)
	}
	structure, ok := child.Selector["structure"].(map[string]any)
	if !ok || structure["pointer"] != "/value" {
		t.Fatalf("long structured child lost JSON pointer: %+v", child.Selector)
	}
	encoded, err := json.Marshal(child.Payload)
	if err != nil {
		t.Fatal(err)
	}
	var payload struct {
		Path  string `json:"path"`
		Value string `json:"value"`
	}
	if err := json.Unmarshal(encoded, &payload); err != nil || payload.Path != "/value" || payload.Value != value {
		t.Fatalf("long structured child payload changed: payload=%+v err=%v", payload, err)
	}
}

func TestSilverAtomicEvidenceIsDeterministicAcrossRestartWithoutModel(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	content := "Name,Notes\nJonas Sandvall,\"Birthday: 1986-08-27\nCity: Stockholm\"\n"
	item := putSilverBronze(t, bronze, "56565656-5656-4565-8565-565656565656", "contacts.csv", "text/csv", 1, content)
	service, err := newSilverServiceWithModel(root+"/silver", bronze, nil)
	if err != nil {
		t.Fatal(err)
	}
	service.processNext(context.Background())
	first := service.snapshot()
	if len(first.Sources) != 1 || first.Sources[0].Coverage.SemanticSkipReason != silverSkipModelUnavailable {
		t.Fatalf("deterministic Silver did not publish without a model: %+v", first)
	}
	ids := make([]string, 0, len(first.Evidence))
	for _, evidence := range first.Evidence {
		if evidence.BronzeSourceID != item.ID || evidence.BronzeContentSHA256 != item.Hash {
			t.Fatalf("evidence does not trace to existing Bronze: %+v", evidence)
		}
		ids = append(ids, evidence.ID)
	}
	sort.Strings(ids)

	restarted, err := newSilverServiceWithModel(root+"/silver", bronze, nil)
	if err != nil {
		t.Fatal(err)
	}
	restartedIDs := make([]string, 0, len(restarted.snapshot().Evidence))
	for _, evidence := range restarted.snapshot().Evidence {
		restartedIDs = append(restartedIDs, evidence.ID)
	}
	sort.Strings(restartedIDs)
	if strings.Join(ids, "|") != strings.Join(restartedIDs, "|") {
		t.Fatalf("restart changed deterministic evidence IDs: before=%q after=%q", ids, restartedIDs)
	}
	_, reader, err := bronze.openContent(item.ID)
	if err != nil {
		t.Fatal(err)
	}
	stored, readErr := io.ReadAll(reader)
	closeErr := reader.Close()
	if readErr != nil || closeErr != nil || string(stored) != content {
		t.Fatalf("parsing changed canonical Bronze: content=%q read=%v close=%v", stored, readErr, closeErr)
	}
}

func TestSilverModelChangePreservesDeterministicRepresentation(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	putSilverBronze(t, bronze, "68686868-6868-4686-8686-686868686868", "contacts.csv", "text/csv", 1,
		"Name,Notes\nJonas Sandvall,\"Birthday: 1986-08-27\nCity: Stockholm\"\n")
	inputs := map[string][]semanticInput{}
	emptyModel := func(revision string) semanticModel {
		return testSemanticModel{id: "replaceable-model", revision: revision, run: func(input semanticInput) (semanticResult, error) {
			inputs[revision] = append(inputs[revision], input)
			return emptySemanticResult(input), nil
		}}
	}
	first, err := newSilverServiceWithModel(root+"/silver", bronze, emptyModel("one"))
	if err != nil {
		t.Fatal(err)
	}
	first.processNext(context.Background())
	before := first.snapshot()

	second, err := newSilverServiceWithModel(root+"/silver", bronze, emptyModel("two"))
	if err != nil {
		t.Fatal(err)
	}
	second.processNext(context.Background())
	after := second.snapshot()
	if len(after.Sources) != 1 || after.Sources[0].ModelRevision != "two" {
		t.Fatalf("model-dependent generation did not change: %+v", after.Sources)
	}
	ids := func(snapshot silverSnapshot) string {
		values := make([]string, 0)
		for _, evidence := range snapshot.Evidence {
			values = append(values, "e:"+evidence.ID)
		}
		for _, observation := range snapshot.Observations {
			if observation.Producer.ProcessorID == silverExtractionID {
				values = append(values, "o:"+observation.ID)
			}
		}
		sort.Strings(values)
		return strings.Join(values, "|")
	}
	if ids(before) != ids(after) {
		t.Fatalf("model change altered deterministic Evidence/observations: before=%s after=%s", ids(before), ids(after))
	}
	if len(inputs["two"]) != 1 || len(inputs["two"][0].Contexts) != 1 {
		t.Fatalf("model change did not rebuild knowledge from persisted structural context: %+v", inputs["two"])
	}
	contextID := inputs["two"][0].Contexts[0].ID
	for _, fragment := range inputs["two"][0].Fragments {
		if fragment.Kind == "parsed-table-row" {
			t.Fatalf("structural-only parent leaked into rebuilt semantic input: %+v", fragment)
		}
		if fragment.Kind == "parsed-key-value" && fragment.ParentContextID != contextID {
			t.Fatalf("rebuilt atomic fragment lost its persisted parent context: %+v", fragment)
		}
	}
}

func TestSilverBinaryBronzeDoesNotPretendToBeText(t *testing.T) {
	fragments, supported, err := parseSilverText(bronzeItem{Title: "image.bin", Mime: "application/octet-stream"}, []byte{0, 1, 2, 3})
	if err != nil || supported || fragments != nil {
		t.Fatalf("binary detection: fragments=%v supported=%v err=%v", fragments, supported, err)
	}
}

type countingReader struct {
	reader io.Reader
	read   int
}

func (r *countingReader) Read(value []byte) (int, error) {
	n, err := r.reader.Read(value)
	r.read += n
	return n, err
}

func TestSilverRejectsKnownBinaryWithoutReadingContent(t *testing.T) {
	reader := &countingReader{reader: bytes.NewReader(make([]byte, silverMaximumInputBytes+1))}
	result, err := parseSilverReader(bronzeItem{Title: "photo.jpg", Mime: "image/jpeg"}, reader)
	if err != nil || result.Coverage.ExtractionState != silverExtractionSkipped ||
		result.Coverage.SemanticSkipReason != silverSkipUnsupportedContent || result.Fragments != nil || reader.read != 0 {
		t.Fatalf("binary read was not bounded by metadata: read=%d result=%+v err=%v", reader.read, result, err)
	}
}

func TestSilverReaderHasHardInputBound(t *testing.T) {
	reader := &countingReader{reader: bytes.NewReader(bytes.Repeat([]byte("x"), silverMaximumInputBytes+1024))}
	result, err := parseSilverReader(bronzeItem{Title: "unknown.dat", Mime: "application/octet-stream"}, reader)
	if err != nil || result.Coverage.ExtractionState != silverExtractionSkipped ||
		result.Coverage.SemanticSkipReason != silverSkipSourceTooLarge || result.Fragments != nil ||
		reader.read > silverMaximumInputBytes+1 {
		t.Fatalf("input bound: read=%d result=%+v err=%v", reader.read, result, err)
	}
}

func TestSilverEvidenceRangesReferToOriginalBronzeBytes(t *testing.T) {
	tests := []struct {
		name, title, mime, content string
	}{
		{"generic BOM and whitespace", "note.txt", "text/plain", "\ufeff  " + strings.Repeat("å", silverMaximumBatchBytes) + "  "},
		{"markdown whitespace", "note.md", "text/markdown", "  # Heading  \n\n  Body text  \n"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fragments, supported, err := parseSilverText(bronzeItem{Title: test.title, Mime: test.mime}, []byte(test.content))
			if err != nil || !supported || len(fragments) == 0 {
				t.Fatalf("parse: supported=%v fragments=%v err=%v", supported, fragments, err)
			}
			for _, fragment := range fragments {
				if fragment.Selector["kind"] != "utf8-byte-range" {
					continue
				}
				start := fragment.Selector["start_byte"].(int)
				end := fragment.Selector["end_byte"].(int)
				if got := test.content[start:end]; got != fragment.Text {
					t.Fatalf("range %d:%d selected %q, want %q", start, end, got, fragment.Text)
				}
			}
		})
	}
}

func TestSilverEntityAggregatesSourcesAndDeletionRemovesDerivedData(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	first := putSilverBronze(t, bronze, "33333333-3333-4333-8333-333333333333", "first.txt", "text/plain", 1, "Ada Lovelace wrote this.")
	second := putSilverBronze(t, bronze, "44444444-4444-4444-8444-444444444444", "second.txt", "text/plain", 1, "Ada Lovelace reviewed this.")
	service, err := newSilverServiceWithModel(root+"/silver", bronze, namedPeopleTestModel())
	if err != nil {
		t.Fatal(err)
	}
	attachSilverQueue(service, bronze)
	service.processNext(context.Background())
	service.processNext(context.Background())
	snapshot := service.snapshot()
	if len(snapshot.Entities) != 1 || len(snapshot.Claims) != 2 || len(snapshot.Sources) != 2 {
		t.Fatalf("same exact label did not aggregate across sources: entities=%d claims=%d sources=%d", len(snapshot.Entities), len(snapshot.Claims), len(snapshot.Sources))
	}
	deleted := first
	deleted.Revision = 2
	deleted.Modified = 2
	deleted.Deleted = true
	deleted.Hash = ""
	deleted.Size = 0
	if response := bronzeRequest(t, bronze, http.MethodDelete, deleted, nil); response.Code != http.StatusOK {
		t.Fatalf("delete Bronze: %d", response.Code)
	}
	snapshot = service.snapshot()
	if len(snapshot.Sources) != 1 || snapshot.Sources[0].BronzeSourceID != second.ID || len(snapshot.Claims) != 1 {
		t.Fatalf("deletion left authoritative derived records: %+v", snapshot)
	}
	deleted = second
	deleted.Revision = 2
	deleted.Modified = 2
	deleted.Deleted = true
	deleted.Hash = ""
	deleted.Size = 0
	if response := bronzeRequest(t, bronze, http.MethodDelete, deleted, nil); response.Code != http.StatusOK {
		t.Fatalf("delete final Bronze: %d", response.Code)
	}
	snapshot = service.snapshot()
	if len(snapshot.Sources) != 0 || len(snapshot.Evidence) != 0 || len(snapshot.Observations) != 0 ||
		len(snapshot.Entities) != 0 || len(snapshot.Claims) != 0 {
		t.Fatalf("final deletion left Silver representations behind: %+v", snapshot)
	}
}

func TestSilverEntityResolutionRequiresMatchingLabelAndType(t *testing.T) {
	service := &silverService{state: silverDiskState{Entities: map[string]silverEntityRecord{}}}

	person, ok := service.resolveEntityLocked("Atlas", "atlas", "person")
	if !ok {
		t.Fatal("person candidate was not resolved")
	}
	samePerson, ok := service.resolveEntityLocked("  Atlas  ", "atlas", "person")
	if !ok || samePerson.ID != person.ID {
		t.Fatalf("matching label and type did not reuse Entity: first=%+v second=%+v", person, samePerson)
	}
	organization, ok := service.resolveEntityLocked("Atlas", "atlas", "organization")
	if !ok || organization.ID == person.ID {
		t.Fatalf("incompatible types were merged: person=%+v organization=%+v", person, organization)
	}
	untyped, ok := service.resolveEntityLocked("Atlas", "atlas", "")
	if !ok || untyped.ID == person.ID || untyped.ID == organization.ID {
		t.Fatalf("typeless candidate was merged with a typed Entity: person=%+v organization=%+v untyped=%+v", person, organization, untyped)
	}
	sameUntyped, ok := service.resolveEntityLocked("Atlas", "atlas", "")
	if !ok || sameUntyped.ID != untyped.ID {
		t.Fatalf("sole compatible typeless match was not reused: first=%+v second=%+v", untyped, sameUntyped)
	}
}

func TestSilverEntityResolutionLeavesAmbiguousMatchUnresolved(t *testing.T) {
	service := &silverService{state: silverDiskState{Entities: map[string]silverEntityRecord{
		"first":  {Entity: silverEntity{ID: "first"}, Label: "Alex", Normalized: "alex", Type: "person"},
		"second": {Entity: silverEntity{ID: "second"}, Label: "Alex", Normalized: "alex", Type: "person"},
	}}}
	dataset := silverDataset{}
	checkpoint := silverCheckpoint{Entities: []silverEntityCandidate{{
		ObservationID: "observation", Ref: "alex", Label: "Alex", Normalized: "alex", Type: "person", Confidence: 0.99,
	}}}

	service.resolveCheckpointCandidatesLocked(&dataset, checkpoint, map[string]bool{}, "model", "1")

	if len(dataset.Entities) != 0 || len(dataset.Claims) != 0 || len(service.state.Entities) != 2 {
		t.Fatalf("ambiguous candidate was forced into resolved knowledge: dataset=%+v registry=%+v", dataset, service.state.Entities)
	}
}

func TestSilverEntityTypeBackfillIgnoresGenericTypeAttributes(t *testing.T) {
	resolver := silverProducer{ProcessorID: silverResolverID, ProcessorVersion: "1"}
	service := &silverService{state: silverDiskState{
		Entities: map[string]silverEntityRecord{
			"alex": {Entity: silverEntity{ID: "alex"}, Label: "Alex", Normalized: "alex"},
		},
		Published: map[string]silverDataset{
			"source": {
				Observations: []silverObservation{
					{ID: "entity-observation", Kind: "entity-candidate"},
					{ID: "attribute-observation", Kind: "attribute-candidate"},
				},
				Claims: []silverClaim{
					{SubjectEntityID: "alex", Predicate: "type", Value: json.RawMessage(`"person"`), SupportingObservationIDs: []string{"entity-observation"}, Producer: resolver, State: "active"},
					{SubjectEntityID: "alex", Predicate: "type", Value: json.RawMessage(`"engineer"`), SupportingObservationIDs: []string{"attribute-observation", "entity-observation"}, Producer: resolver, State: "active"},
				},
			},
		},
	}}

	service.backfillEntityTypesLocked()

	record := service.state.Entities["alex"]
	if record.Type != "person" || record.TypeAmbiguous {
		t.Fatalf("generic type attribute polluted entity type backfill: %+v", record)
	}
}

func TestSilverSnapshotRequiresPairedSelf(t *testing.T) {
	identity, err := loadIdentity(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	client := testClientCert(t)
	leaf, err := x509.ParseCertificate(client.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	identity.state.SelfPin = fingerprint(leaf)
	handler := testLanHandler(t, identity)

	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/v1/silver", nil))
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("untrusted snapshot: %d", response.Code)
	}

	request := httptest.NewRequest(http.MethodGet, "/v1/silver", nil)
	request.TLS = &tls.ConnectionState{PeerCertificates: []*x509.Certificate{leaf}}
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("trusted snapshot: %d: %s", response.Code, response.Body.String())
	}
	var snapshot silverSnapshot
	if err := json.Unmarshal(response.Body.Bytes(), &snapshot); err != nil {
		t.Fatal(err)
	}
	if snapshot.SchemaVersion != silverSchemaVersion {
		t.Fatalf("schema version: %d", snapshot.SchemaVersion)
	}
}

func TestSilverWorkerContinuesWithoutSelfConnection(t *testing.T) {
	root := t.TempDir()
	bronze := newBronzeStore(root + "/bronze")
	service, err := newSilverService(root+"/silver", bronze)
	if err != nil {
		t.Fatal(err)
	}
	attachSilverQueue(service, bronze)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	service.start(ctx)
	t.Cleanup(service.wait)
	putSilverBronze(t, bronze, "55555555-5555-4555-8555-555555555555", "background.txt", "text/plain", 1, "Ada Lovelace worked while Self was absent.")
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if len(service.snapshot().Sources) == 1 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("Source-owned worker did not publish without a Self request")
}
