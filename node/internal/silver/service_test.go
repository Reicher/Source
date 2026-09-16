package silver

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	localai "source.local/node/internal/ai"
	"source.local/node/internal/apperror"
	"source.local/node/internal/database"
	"source.local/node/internal/security"
	"source.local/node/internal/storage"
)

type testAI struct{ calls int }

func (a *testAI) Status(context.Context) bool { return true }
func (a *testAI) State(context.Context) localai.RuntimeState {
	return localai.RuntimeState{Availability: "ready", Capabilities: a.Capabilities()}
}

type resumableAI struct {
	mu      sync.Mutex
	calls   int
	failAt  int
	blockAt int
	started chan int
	release chan struct{}
}

func (a *resumableAI) Status(context.Context) bool { return true }
func (a *resumableAI) State(context.Context) localai.RuntimeState {
	return localai.RuntimeState{Availability: "ready", Capabilities: a.Capabilities()}
}
func (a *resumableAI) Capabilities() map[string]any { return map[string]any{"modelId": "test-model"} }
func (a *resumableAI) StreamChat(ctx context.Context, _ []localai.Message, yield func(localai.Event) error) error {
	a.mu.Lock()
	a.calls++
	call, failAt, blockAt := a.calls, a.failAt, a.blockAt
	a.mu.Unlock()
	if a.started != nil {
		select {
		case a.started <- call:
		default:
		}
	}
	if call == blockAt {
		if a.release == nil {
			<-ctx.Done()
			return ctx.Err()
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-a.release:
		}
	}
	if call == failAt {
		return errors.New("inference interrupted")
	}
	return yield(localai.Event{Type: "delta", Text: `{"entities":[],"claims":[]}`})
}

func (a *resumableAI) callCount() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.calls
}

func (a *resumableAI) unblock() {
	a.mu.Lock()
	a.blockAt = 0
	a.mu.Unlock()
}
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
	defer func() { service.Close() }()

	request := refinementRequest(t, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Source is active")
	first, err := service.Refine(context.Background(), user.ID, request)
	firstJob := waitForJob(t, service, user.ID, first.Job.ID, "completed")
	if err != nil || !first.BronzeAccepted || firstJob.Receipt == nil || ai.calls != 1 {
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
	service.Close()
	service = New(db, store, ai, 1024*1024, func() time.Time { return now })
	for _, operation := range []string{
		"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
		"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
	} {
		request.OperationID = operation
		retry, retryErr := service.Refine(context.Background(), user.ID, request)
		if retryErr != nil || retry.Job.State != "completed" || ai.calls != 1 {
			t.Fatalf("retry = %#v calls=%d error=%v", retry, ai.calls, retryErr)
		}
	}
	heads, err = db.SyncHeads(user.ID, Collection, canonicalObjectID(Collection))
	if err != nil || len(heads) != 1 || heads[0].RevisionID != firstJob.Receipt.RevisionID {
		t.Fatalf("reconnect changed heads = %#v error=%v", heads, err)
	}

	now = now.Add(time.Second)
	changedRequest := refinementRequest(t, "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "Source is maintained")
	changed, err := service.Refine(context.Background(), user.ID, changedRequest)
	changedJob := waitForJob(t, service, user.ID, changed.Job.ID, "completed")
	if err != nil || changedJob.Receipt == nil || ai.calls != 2 {
		t.Fatalf("reprocessing = %#v calls=%d error=%v", changed, ai.calls, err)
	}
	heads, err = db.SyncHeads(user.ID, Collection, canonicalObjectID(Collection))
	if err != nil || len(heads) != 1 || heads[0].RevisionID == firstJob.Receipt.RevisionID ||
		len(heads[0].ParentRevisionIDs) != 1 || heads[0].ParentRevisionIDs[0] != firstJob.Receipt.RevisionID {
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

	// A distinct no-op removal must still be durable. Replaying it after the
	// source is re-added must not remove the newer generation.
	noopRemoval := removal
	noopRemoval.OperationID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
	if result, removeErr := service.Remove(user.ID, noopRemoval); removeErr != nil || result.BronzeRemoved || result.SilverChanged {
		t.Fatalf("no-op removal = %#v error=%v", result, removeErr)
	}
	now = now.Add(time.Second)
	readdedRequest := refinementRequest(t, "ffffffff-ffff-4fff-8fff-ffffffffffff", "Source is active again")
	readded, err := service.Refine(context.Background(), user.ID, readdedRequest)
	readdedJob := waitForJob(t, service, user.ID, readded.Job.ID, "completed")
	if err != nil || readdedJob.Receipt == nil || ai.calls != 3 {
		t.Fatalf("re-added source = %#v calls=%d error=%v", readded, ai.calls, err)
	}
	if replayed, replayErr := service.Remove(user.ID, noopRemoval); replayErr != nil || replayed.BronzeRemoved || replayed.SilverChanged {
		t.Fatalf("replayed no-op removal = %#v error=%v", replayed, replayErr)
	}
	heads, err = db.SyncHeads(user.ID, Collection, canonicalObjectID(Collection))
	if err != nil || len(heads) != 1 || heads[0].RevisionID != readdedJob.Receipt.RevisionID {
		t.Fatalf("replayed no-op removal changed Silver = %#v error=%v", heads, err)
	}

	oversized := refinementRequest(t, "99999999-9999-4999-8999-999999999999", strings.Repeat("x", maximumRefinementBytes+1))
	if _, refineErr := service.Refine(context.Background(), user.ID, oversized); errorCode(refineErr) != "silver_source_too_large" {
		t.Fatalf("oversized refinement error = %v", refineErr)
	}
}

func waitForJob(t *testing.T, service *Service, userID, jobID, want string) Job {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		job, err := service.Job(userID, jobID)
		if err != nil {
			t.Fatal(err)
		}
		if job.State == want {
			return job
		}
		if job.State == "failed" || job.State == "cancelled" {
			t.Fatalf("job reached %s while waiting for %s: %#v", job.State, want, job)
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("job %s did not reach %s", jobID, want)
	return Job{}
}

func TestFinalQuotaIncludesAcceptedBronzeAndGeneratedSilver(t *testing.T) {
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
	user, _, err := db.CreatePairedUser("Robin", 100, "recovery", "envelope", database.NewClient{
		ID: "client-a", DisplayName: "Phone", PublicKey: "public", CredentialHash: "credential", ProtocolVersion: 1,
	}, 1)
	if err != nil {
		t.Fatal(err)
	}
	if err = db.UpsertSnapshot(user.ID, "source-client", "snapshot", 30, strings.Repeat("a", 64), 1); err != nil {
		t.Fatal(err)
	}
	if _, _, err = db.AcceptSilverRefinementSource(user.ID, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", strings.Repeat("b", 64), database.SilverRefinementSource{
		SourceID: "source-1", Name: "source.txt", SourceType: "file", ContentSHA256: strings.Repeat("c", 64), Plaintext: strings.Repeat("x", 40), AcceptedAt: 1,
	}); err != nil {
		t.Fatal(err)
	}
	service := New(db, nil, nil, 1024, time.Now)
	if err = service.checkFinalQuota(user.ID, 31); errorCode(err) != "storage_quota_exceeded" {
		t.Fatalf("combined quota error = %v", err)
	}
	if err = service.checkFinalQuota(user.ID, 30); err != nil {
		t.Fatalf("exact combined quota rejected: %v", err)
	}
}

func TestRefinementRetriesFromDurableCompletedBatch(t *testing.T) {
	db, user := refinementTestDatabase(t)
	defer db.Close()
	ai := &resumableAI{failAt: 2}
	now := time.UnixMilli(1_800_000_000_000)
	service := New(db, storage.New(db, t.TempDir(), 20, func() time.Time { return now }), ai, 1024*1024, func() time.Time { return now })
	defer service.Close()

	request := refinementRequest(t, "11111111-1111-4111-8111-111111111111", strings.Repeat("durable batch text ", 400))
	accepted, err := service.Refine(context.Background(), user.ID, request)
	if err != nil {
		t.Fatal(err)
	}
	failed := waitForJob(t, service, user.ID, accepted.Job.ID, "failed")
	if failed.CompletedBatches != 1 || failed.TotalBatches < 3 {
		t.Fatalf("failed progress = %d/%d", failed.CompletedBatches, failed.TotalBatches)
	}
	ai.mu.Lock()
	ai.failAt = 0
	ai.mu.Unlock()
	if _, err = service.Retry(user.ID, accepted.Job.ID); err != nil {
		t.Fatal(err)
	}
	completed := waitForJob(t, service, user.ID, accepted.Job.ID, "completed")
	if completed.CompletedBatches != completed.TotalBatches {
		t.Fatalf("completed progress = %d/%d", completed.CompletedBatches, completed.TotalBatches)
	}
	if got, want := ai.callCount(), completed.TotalBatches+1; got != want {
		t.Fatalf("inference calls = %d, want %d (completed checkpoint should be reused)", got, want)
	}
}

func TestAcceptedJobSurvivesClientCancellationAndNodeRestart(t *testing.T) {
	databasePath := filepath.Join(t.TempDir(), "source.sqlite")
	db, user := refinementTestDatabaseAt(t, databasePath)
	ai := &resumableAI{blockAt: 2, started: make(chan int, 8)}
	now := time.UnixMilli(1_800_000_000_000)
	storageRoot := t.TempDir()
	store := storage.New(db, storageRoot, 20, func() time.Time { return now })
	service := New(db, store, ai, 1024*1024, func() time.Time { return now })

	request := refinementRequest(t, "22222222-2222-4222-8222-222222222222", strings.Repeat("restartable batch text ", 400))
	requestContext, disconnect := context.WithCancel(context.Background())
	accepted, err := service.Refine(requestContext, user.ID, request)
	if err != nil {
		t.Fatal(err)
	}
	disconnect()
	deadline := time.After(3 * time.Second)
	for {
		select {
		case call := <-ai.started:
			if call == 2 {
				goto interrupted
			}
		case <-deadline:
			t.Fatal("job did not reach its second batch")
		}
	}

interrupted:
	job, err := service.Job(user.ID, accepted.Job.ID)
	if err != nil || job.CompletedBatches != 1 || job.State != "running" {
		t.Fatalf("pre-restart job = %#v error=%v", job, err)
	}
	service.Close()
	if err = db.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = database.Open(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ai.unblock()
	store = storage.New(db, storageRoot, 20, func() time.Time { return now })
	service = New(db, store, ai, 1024*1024, func() time.Time { return now })
	defer service.Close()
	completed := waitForJob(t, service, user.ID, accepted.Job.ID, "completed")
	if completed.CompletedBatches != completed.TotalBatches {
		t.Fatalf("recovered progress = %d/%d", completed.CompletedBatches, completed.TotalBatches)
	}
	if got, want := ai.callCount(), completed.TotalBatches+1; got != want {
		t.Fatalf("inference calls after restart = %d, want %d", got, want)
	}
}

func TestPauseFinishesCurrentBatchAndResumeKeepsCheckpoint(t *testing.T) {
	db, user := refinementTestDatabase(t)
	defer db.Close()
	ai := &resumableAI{blockAt: 1, started: make(chan int, 4), release: make(chan struct{})}
	now := time.UnixMilli(1_800_000_000_000)
	service := New(db, storage.New(db, t.TempDir(), 20, func() time.Time { return now }), ai, 1024*1024, func() time.Time { return now })
	defer service.Close()

	request := refinementRequest(t, "33333333-3333-4333-8333-333333333333", strings.Repeat("pausable batch text ", 350))
	accepted, err := service.Refine(context.Background(), user.ID, request)
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-ai.started:
	case <-time.After(3 * time.Second):
		t.Fatal("job did not start")
	}
	requested, err := service.Pause(user.ID, accepted.Job.ID)
	if err != nil || requested.State != "running" {
		t.Fatalf("pause request = %#v error=%v", requested, err)
	}
	close(ai.release)
	paused := waitForJob(t, service, user.ID, accepted.Job.ID, "paused")
	if paused.CompletedBatches != 1 {
		t.Fatalf("paused after %d batches, want 1", paused.CompletedBatches)
	}
	ai.unblock()
	if _, err = service.Resume(user.ID, accepted.Job.ID); err != nil {
		t.Fatal(err)
	}
	completed := waitForJob(t, service, user.ID, accepted.Job.ID, "completed")
	if got, want := ai.callCount(), completed.TotalBatches; got != want {
		t.Fatalf("resume calls = %d, want %d", got, want)
	}
}

func TestNodeRunsOneQueuedRefinementAtATimeAndCancelIsTerminal(t *testing.T) {
	db, user := refinementTestDatabase(t)
	defer db.Close()
	ai := &resumableAI{blockAt: 1, started: make(chan int, 4)}
	now := time.UnixMilli(1_800_000_000_000)
	service := New(db, storage.New(db, t.TempDir(), 20, func() time.Time { return now }), ai, 1024*1024, func() time.Time { return now })
	defer service.Close()

	first, err := service.Refine(context.Background(), user.ID,
		refinementRequest(t, "44444444-4444-4444-8444-444444444444", "first source"))
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-ai.started:
	case <-time.After(3 * time.Second):
		t.Fatal("first job did not start")
	}
	secondRequest := refinementRequest(t, "55555555-5555-4555-8555-555555555555", "second source")
	secondRequest.Source.ID = "source-2"
	second, err := service.Refine(context.Background(), user.ID, secondRequest)
	if err != nil {
		t.Fatal(err)
	}
	queued, err := service.Job(user.ID, second.Job.ID)
	if err != nil || queued.State != "queued" || ai.callCount() != 1 {
		t.Fatalf("second job = %#v calls=%d error=%v", queued, ai.callCount(), err)
	}
	cancelled, err := service.Cancel(user.ID, first.Job.ID)
	if err != nil || cancelled.State != "cancelled" {
		t.Fatalf("cancelled job = %#v error=%v", cancelled, err)
	}
	completed := waitForJob(t, service, user.ID, second.Job.ID, "completed")
	if completed.State != "completed" || ai.callCount() != 2 {
		t.Fatalf("completed second job = %#v calls=%d", completed, ai.callCount())
	}
	retried, err := service.Retry(user.ID, first.Job.ID)
	if err != nil || retried.State != "cancelled" {
		t.Fatalf("terminal cancel was retried: %#v error=%v", retried, err)
	}
}

func TestFailedOlderGenerationCannotOverwriteCompletedNewerSilver(t *testing.T) {
	db, user := refinementTestDatabase(t)
	defer db.Close()
	ai := &resumableAI{failAt: 2}
	now := time.UnixMilli(1_800_000_000_000)
	store := storage.New(db, t.TempDir(), 20, func() time.Time { return now })
	service := New(db, store, ai, 1024*1024, func() time.Time { return now })
	defer service.Close()

	v1, err := service.Refine(context.Background(), user.ID,
		refinementRequest(t, "66666666-6666-4666-8666-666666666666", strings.Repeat("old generation text ", 350)))
	if err != nil {
		t.Fatal(err)
	}
	waitForJob(t, service, user.ID, v1.Job.ID, "failed")
	ai.mu.Lock()
	ai.failAt = 0
	ai.mu.Unlock()

	v2, err := service.Refine(context.Background(), user.ID,
		refinementRequest(t, "77777777-7777-4777-8777-777777777777", "new generation text"))
	if err != nil {
		t.Fatal(err)
	}
	waitForJob(t, service, user.ID, v2.Job.ID, "completed")
	newHead := silverHeadID(t, db, user.ID)

	if _, err = service.Retry(user.ID, v1.Job.ID); err != nil {
		t.Fatal(err)
	}
	stale := waitForJob(t, service, user.ID, v1.Job.ID, "failed")
	if stale.Error == nil || stale.Error.Code != "silver_generation_superseded" {
		t.Fatalf("stale retry = %#v", stale)
	}
	if got := silverHeadID(t, db, user.ID); got != newHead {
		t.Fatalf("stale retry changed Silver head from %s to %s", newHead, got)
	}
}

func TestPausedOlderGenerationCannotOverwriteCompletedNewerSilver(t *testing.T) {
	db, user := refinementTestDatabase(t)
	defer db.Close()
	ai := &resumableAI{blockAt: 1, started: make(chan int, 4), release: make(chan struct{})}
	now := time.UnixMilli(1_800_000_000_000)
	store := storage.New(db, t.TempDir(), 20, func() time.Time { return now })
	service := New(db, store, ai, 1024*1024, func() time.Time { return now })
	defer service.Close()

	v1, err := service.Refine(context.Background(), user.ID,
		refinementRequest(t, "88888888-8888-4888-8888-888888888888", strings.Repeat("paused old generation ", 350)))
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-ai.started:
	case <-time.After(3 * time.Second):
		t.Fatal("old generation did not start")
	}
	if _, err = service.Pause(user.ID, v1.Job.ID); err != nil {
		t.Fatal(err)
	}
	close(ai.release)
	waitForJob(t, service, user.ID, v1.Job.ID, "paused")

	v2, err := service.Refine(context.Background(), user.ID,
		refinementRequest(t, "99999999-9999-4999-8999-999999999998", "newer source generation"))
	if err != nil {
		t.Fatal(err)
	}
	waitForJob(t, service, user.ID, v2.Job.ID, "completed")
	newHead := silverHeadID(t, db, user.ID)
	ai.unblock()
	if _, err = service.Resume(user.ID, v1.Job.ID); err != nil {
		t.Fatal(err)
	}
	stale := waitForJob(t, service, user.ID, v1.Job.ID, "failed")
	if stale.Error == nil || stale.Error.Code != "silver_generation_superseded" {
		t.Fatalf("stale resume = %#v", stale)
	}
	if got := silverHeadID(t, db, user.ID); got != newHead {
		t.Fatalf("stale resume changed Silver head from %s to %s", newHead, got)
	}
}

func TestJobFromOlderProcessorVersionFailsBeforeUsingCheckpoints(t *testing.T) {
	db, user := refinementTestDatabase(t)
	defer db.Close()
	ai := &resumableAI{}
	now := time.UnixMilli(1_800_000_000_000)
	text := "processor upgrade"
	contentSHA := sha256Text(text)
	stored, _, err := db.AcceptSilverRefinementJob(
		user.ID, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", strings.Repeat("a", 64),
		database.SilverRefinementSource{
			SourceID: "source-1", Name: "source.txt", SourceType: "file",
			ContentSHA256: contentSHA, Plaintext: text, AcceptedAt: now.UnixMilli(),
		}, ExtractionProcessorID, "previous-version", "test-model", "silver", 1, now.UnixMilli(),
	)
	if err != nil {
		t.Fatal(err)
	}
	service := New(db, storage.New(db, t.TempDir(), 20, func() time.Time { return now }), ai, 1024*1024, func() time.Time { return now })
	defer service.Close()
	job := waitForJob(t, service, user.ID, stored.JobID, "failed")
	if job.Error == nil || job.Error.Code != "silver_processor_changed" || ai.callCount() != 0 {
		t.Fatalf("old processor job = %#v calls=%d", job, ai.callCount())
	}
}

func silverHeadID(t *testing.T, db *database.DB, userID string) string {
	t.Helper()
	heads, err := db.SyncHeads(userID, Collection, canonicalObjectID(Collection))
	if err != nil || len(heads) != 1 {
		t.Fatalf("Silver heads = %#v error=%v", heads, err)
	}
	return heads[0].RevisionID
}

func refinementTestDatabase(t *testing.T) (*database.DB, *database.User) {
	return refinementTestDatabaseAt(t, ":memory:")
}

func refinementTestDatabaseAt(t *testing.T, path string) (*database.DB, *database.User) {
	t.Helper()
	db, err := database.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	identity, err := security.GenerateNodeIdentity()
	if err != nil {
		db.Close()
		t.Fatal(err)
	}
	if _, err = db.InitializeNode("Node", identity, "password", 1); err != nil {
		db.Close()
		t.Fatal(err)
	}
	user, _, err := db.CreatePairedUser("Robin", 10*1024*1024, "recovery", "envelope", database.NewClient{
		ID: "client-a", DisplayName: "Phone", PublicKey: "public", CredentialHash: "credential", ProtocolVersion: 1,
	}, 1)
	if err != nil {
		db.Close()
		t.Fatal(err)
	}
	return db, user
}

func errorCode(err error) string {
	if err == nil {
		return ""
	}
	_, code, _ := apperror.Details(err)
	return code
}

func refinementRequest(t *testing.T, operationID, text string) RefineRequest {
	t.Helper()
	digest := sha256.Sum256([]byte(text))
	return RefineRequest{ContractVersion: 1, OperationID: operationID, Source: Source{
		ID: "source-1", Name: "source.txt", SourceType: "file",
		ContentSHA256: hex.EncodeToString(digest[:]), Text: text,
	}}
}
