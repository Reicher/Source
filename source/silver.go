package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	silverSnapshotSchemaVersion    = 2
	silverDiskSchemaVersion        = 2
	silverProcessorID              = "source.silver.deterministic-ingestion"
	silverExtractionID             = "source.silver.format-extraction"
	silverExtractionVersion        = "2"
	silverResolverID               = "source.silver.candidate-resolver"
	silverResolverVersion          = "2"
	silverProcessorVersion         = "1-" + silverExtractionVersion
	silverKnowledgeID              = "source.silver.knowledge"
	silverKnowledgeVersion         = "1-" + semanticProcessorVersion + "-" + silverResolverVersion
	silverResolutionMinimum        = 0.70
	silverMaximumBatchBytes        = 4096
	silverInspectionBytes          = 8192
	silverMaximumInputBytes        = 8 * 1024 * 1024
	silverMaximumContextFields     = 8
	silverMaximumContextCandidates = 32
	silverMaximumContextRunes      = 128
	silverMaximumStructureDepth    = 4
	silverReconcileInterval        = time.Second

	silverRepresentationReady       = "ready"
	silverRepresentationProcessing  = "processing"
	silverRepresentationUnavailable = "unavailable"
	silverRepresentationSkipped     = "skipped"
	silverRepresentationPartial     = "partial"
	silverRepresentationFailed      = "failed"

	silverSkipUnsupportedContent = "unsupported_content"
	silverSkipSourceTooLarge     = "source_too_large"
	silverSkipFragmentTooLarge   = "fragment_exceeds_model_limit"
	silverSkipModelContract      = "model_contract_failure"

	silverDeterministicRepresentation = "deterministic"
	silverKnowledgeRepresentation     = "knowledge"
)

type silverProducer struct {
	ProcessorID      string `json:"processor_id"`
	ProcessorVersion string `json:"processor_version"`
	ModelID          string `json:"model_id,omitempty"`
	ModelRevision    string `json:"model_revision,omitempty"`
}

type silverEvidence struct {
	ID                  string         `json:"id"`
	BronzeSourceID      string         `json:"bronze_source_id"`
	BronzeContentSHA256 string         `json:"bronze_content_sha256"`
	Selector            map[string]any `json:"selector,omitempty"`
	Excerpt             string         `json:"excerpt,omitempty"`
}

type silverObservation struct {
	ID             string               `json:"id"`
	Kind           string               `json:"kind"`
	Payload        json.RawMessage      `json:"payload"`
	EvidenceIDs    []string             `json:"evidence_ids"`
	Text           string               `json:"text,omitempty"`
	Context        *parsedSilverContext `json:"context,omitempty"`
	StructuralOnly bool                 `json:"structural_only,omitempty"`
	Confidence     *float64             `json:"confidence,omitempty"`
	Producer       silverProducer       `json:"producer"`
}

type silverEntity struct {
	ID string `json:"id"`
}

type silverClaim struct {
	ID                       string          `json:"id"`
	SubjectEntityID          string          `json:"subject_entity_id"`
	Predicate                string          `json:"predicate"`
	Value                    json.RawMessage `json:"value,omitempty"`
	ObjectEntityID           string          `json:"object_entity_id,omitempty"`
	SupportingObservationIDs []string        `json:"supporting_observation_ids"`
	Producer                 silverProducer  `json:"producer"`
	State                    string          `json:"state"`
}

type silverRepresentation struct {
	State         string         `json:"state"`
	Producer      silverProducer `json:"producer"`
	InputIdentity string         `json:"input_identity"`
	Error         string         `json:"error,omitempty"`
}

type silverRepresentations struct {
	Deterministic silverRepresentation `json:"deterministic"`
	Knowledge     silverRepresentation `json:"knowledge"`
}

type silverSource struct {
	BronzeSourceID      string                `json:"bronze_source_id"`
	BronzeContentSHA256 string                `json:"bronze_content_sha256"`
	Title               string                `json:"title"`
	Mime                string                `json:"mime"`
	Representations     silverRepresentations `json:"representations"`
	EvidenceIDs         []string              `json:"evidence_ids"`
	ObservationIDs      []string              `json:"observation_ids"`
	EntityIDs           []string              `json:"entity_ids"`
	ClaimIDs            []string              `json:"claim_ids"`
	Stale               bool                  `json:"stale,omitempty"`
}

type silverDataset struct {
	Source       silverSource        `json:"source"`
	Evidence     []silverEvidence    `json:"evidence"`
	Observations []silverObservation `json:"observations"`
	Entities     []silverEntity      `json:"entities"`
	Claims       []silverClaim       `json:"claims"`
	PublishedAt  int64               `json:"published_at"`
}

type silverProcessing struct {
	BronzeSourceID   string `json:"bronze_source_id"`
	Representation   string `json:"representation"`
	State            string `json:"state"`
	CompletedBatches int    `json:"completed_batches"`
	TotalBatches     int    `json:"total_batches"`
	Error            string `json:"error,omitempty"`
	Retryable        bool   `json:"retryable"`
}

type silverSnapshot struct {
	SchemaVersion int                 `json:"schema_version"`
	Revision      int64               `json:"revision"`
	Sources       []silverSource      `json:"sources"`
	Evidence      []silverEvidence    `json:"evidence"`
	Observations  []silverObservation `json:"observations"`
	Entities      []silverEntity      `json:"entities"`
	Claims        []silverClaim       `json:"claims"`
	Processing    []silverProcessing  `json:"processing"`
	Jobs          sourceJobSnapshot   `json:"jobs"`
	Error         string              `json:"error,omitempty"`
}

type silverEntityCandidate struct {
	ObservationID string  `json:"observation_id"`
	Ref           string  `json:"ref"`
	Label         string  `json:"label"`
	Type          string  `json:"type,omitempty"`
	Normalized    string  `json:"normalized"`
	Confidence    float64 `json:"confidence"`
}

type silverAttributeCandidate struct {
	ObservationID string          `json:"observation_id"`
	SubjectRef    string          `json:"subject_ref"`
	Predicate     string          `json:"predicate"`
	Value         json.RawMessage `json:"value"`
	Confidence    float64         `json:"confidence"`
}

type silverRelationshipCandidate struct {
	ObservationID string  `json:"observation_id"`
	SubjectRef    string  `json:"subject_ref"`
	Predicate     string  `json:"predicate"`
	ObjectRef     string  `json:"object_ref"`
	Confidence    float64 `json:"confidence"`
}

type silverCheckpoint struct {
	BatchIndex                 int                           `json:"batch_index"`
	BatchSHA256                string                        `json:"batch_sha256"`
	Evidence                   []silverEvidence              `json:"evidence"`
	Observations               []silverObservation           `json:"observations"`
	Entities                   []silverEntityCandidate       `json:"entity_candidates,omitempty"`
	Attributes                 []silverAttributeCandidate    `json:"attribute_candidates,omitempty"`
	Relationships              []silverRelationshipCandidate `json:"relationship_candidates,omitempty"`
	SemanticFragments          int                           `json:"semantic_fragments,omitempty"`
	SemanticCompletedFragments int                           `json:"semantic_completed_fragments,omitempty"`
	SkipReason                 string                        `json:"skip_reason,omitempty"`
}

type silverProcessorJob struct {
	ID                  string             `json:"id"`
	BronzeSourceID      string             `json:"bronze_source_id"`
	BronzeContentSHA256 string             `json:"bronze_content_sha256"`
	Title               string             `json:"title"`
	Mime                string             `json:"mime"`
	Representation      string             `json:"representation"`
	Producer            silverProducer     `json:"producer"`
	InputIdentity       string             `json:"input_identity"`
	State               string             `json:"state"`
	TotalBatches        int                `json:"total_batches"`
	Checkpoints         []silverCheckpoint `json:"checkpoints,omitempty"`
	Error               string             `json:"error,omitempty"`
	Attempts            int                `json:"attempts,omitempty"`
	RetryAt             int64              `json:"retry_at,omitempty"`
	Retryable           bool               `json:"retryable"`
	AcceptedAt          int64              `json:"accepted_at"`
	UpdatedAt           int64              `json:"updated_at"`
}

type silverDiskState struct {
	SchemaVersion int                      `json:"schema_version"`
	Revision      int64                    `json:"revision"`
	DataRevision  int64                    `json:"data_revision"`
	NextJob       int64                    `json:"next_job"`
	Jobs          []silverProcessorJob     `json:"jobs"`
	Published     map[string]silverDataset `json:"published"`
}

type silverService struct {
	mu                     sync.Mutex
	path                   string
	bronze                 *bronzeStore
	configuration          silverConfiguration
	state                  silverDiskState
	persisted              []byte
	wake                   chan struct{}
	worker                 sync.WaitGroup
	semantic               semanticModel
	afterCheckpoint        func(string, int)
	onDeterministicPublish func()
	statusError            string
	pendingPersistence     bool
}

type silverProcessError struct {
	err       error
	retryable bool
}

func (e *silverProcessError) Error() string { return e.err.Error() }
func (e *silverProcessError) Unwrap() error { return e.err }

func permanentSilverProcessError(err error) error {
	return &silverProcessError{err: err, retryable: false}
}

func silverProcessErrorIsRetryable(err error) bool {
	var classified *silverProcessError
	if errors.As(err, &classified) {
		return classified.retryable
	}
	return true
}

func newSilverService(dir string, bronze *bronzeStore) (*silverService, error) {
	configuration, err := sourceConfigurationFromEnvironment()
	if err != nil {
		return nil, err
	}
	model, err := semanticModelFromEnvironment(configuration.Model)
	if err != nil {
		return nil, err
	}
	return newSilverServiceWithConfiguration(dir, bronze, model, configuration.Silver)
}

func newSilverServiceWithModel(dir string, bronze *bronzeStore, model semanticModel) (*silverService, error) {
	return newSilverServiceWithConfiguration(dir, bronze, model, defaultSourceConfiguration().Silver)
}

func newSilverServiceWithConfiguration(dir string, bronze *bronzeStore, model semanticModel, configuration silverConfiguration) (*silverService, error) {
	if configuration.SemanticBatchTargetBytes <= 0 {
		return nil, errors.New("Silver semantic batch target must be positive")
	}
	if model != nil && model.maximumInputBytes() <= 0 {
		return nil, errors.New("Source model semantic input limit must be positive")
	}
	s := &silverService{
		path: filepath.Join(dir, "state.json"), bronze: bronze, configuration: configuration,
		wake: make(chan struct{}, 1), semantic: model,
	}
	rebuilt := false
	value, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		s.state = newSilverDiskState(0)
	} else if err != nil {
		return nil, err
	} else {
		var header struct {
			SchemaVersion int   `json:"schema_version"`
			Revision      int64 `json:"revision"`
			DataRevision  int64 `json:"data_revision"`
		}
		if err := json.Unmarshal(value, &header); err != nil {
			return nil, fmt.Errorf("invalid Silver state: %w", err)
		}
		if header.SchemaVersion < silverDiskSchemaVersion {
			// Silver is derived. Replacing an obsolete disk schema is safer and
			// substantially simpler than permanently migrating old generations.
			// Advance the public revision so Self replaces its mirrored snapshot.
			s.state = newSilverDiskState(max(header.Revision, header.DataRevision) + 1)
			rebuilt = true
		} else if header.SchemaVersion > silverDiskSchemaVersion {
			return nil, fmt.Errorf("unsupported newer Silver state version %d", header.SchemaVersion)
		} else if err := json.Unmarshal(value, &s.state); err != nil {
			return nil, fmt.Errorf("invalid Silver state: %w", err)
		}
	}
	if s.state.Published == nil {
		s.state.Published = map[string]silverDataset{}
	}
	s.persisted, err = json.Marshal(s.state)
	if err != nil {
		return nil, err
	}
	if rebuilt {
		if err := savePrivate(s.path, s.persisted); err != nil {
			return nil, err
		}
	}
	recovered := false
	for index := range s.state.Jobs {
		if s.state.Jobs[index].State == "running" {
			s.state.Jobs[index].State = "queued"
			s.state.Jobs[index].Error = ""
			s.state.Jobs[index].RetryAt = 0
			s.state.Jobs[index].Retryable = false
			recovered = true
		}
	}
	if recovered {
		s.state.Revision++
		if err := s.saveLocked(); err != nil {
			return nil, err
		}
	}
	if err := s.reconcile(); err != nil {
		return nil, err
	}
	return s, nil
}

func newSilverDiskState(revision int64) silverDiskState {
	return silverDiskState{
		SchemaVersion: silverDiskSchemaVersion,
		Revision:      revision,
		DataRevision:  revision,
		Published:     map[string]silverDataset{},
	}
}

func (s *silverService) start(ctx context.Context) {
	s.worker.Add(1)
	go func() {
		defer s.worker.Done()
		ticker := time.NewTicker(silverReconcileInterval)
		defer ticker.Stop()
		for {
			if err := s.reconcile(); err != nil {
				log.Printf("Silver reconciliation failed: %v", err)
			}
			for s.processNext(ctx) {
			}
			select {
			case <-ctx.Done():
				return
			case <-s.wake:
			case <-ticker.C:
			}
		}
	}()
	s.signal()
}

func (s *silverService) wait() { s.worker.Wait() }

func (s *silverService) signal() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *silverService) retryDueFailuresLocked(now time.Time) bool {
	changed := false
	for index := range s.state.Jobs {
		job := &s.state.Jobs[index]
		if job.State != "failed" || !job.Retryable || job.RetryAt == 0 || job.RetryAt > now.UnixMilli() {
			continue
		}
		job.State = "queued"
		job.Error = ""
		job.RetryAt = 0
		job.Retryable = false
		job.UpdatedAt = now.UnixMilli()
		changed = true
	}
	return changed
}

// reconcile makes the durable Bronze manifest the source of truth for Silver
// work. It repairs missed queue entries and requeues transient failures when due.
func (s *silverService) reconcile() (resultErr error) {
	defer func() {
		s.mu.Lock()
		if resultErr != nil {
			s.statusError = "Silver reconciliation failed: " + resultErr.Error()
		} else if !s.pendingPersistence {
			s.statusError = ""
		}
		s.mu.Unlock()
	}()
	s.mu.Lock()
	if s.pendingPersistence {
		if err := s.persistCurrentLocked(); err != nil {
			s.mu.Unlock()
			return fmt.Errorf("persist pending Silver job state: %w", err)
		}
		s.pendingPersistence = false
	}
	s.mu.Unlock()
	items, err := s.bronze.manifest()
	if err != nil {
		return err
	}
	for _, item := range items {
		s.mu.Lock()
		needed := s.needsReconcileLocked(item)
		s.mu.Unlock()
		if needed {
			if _, err := s.enqueue(item); err != nil {
				return err
			}
		}
	}
	s.mu.Lock()
	retried := s.retryDueFailuresLocked(time.Now())
	if retried {
		s.state.Revision++
		if err := s.saveLocked(); err != nil {
			s.mu.Unlock()
			return err
		}
	}
	s.mu.Unlock()
	if retried {
		s.signal()
	}
	return nil
}

func (s *silverService) needsReconcileLocked(item bronzeItem) bool {
	if item.Deleted {
		if _, ok := s.state.Published[item.ID]; ok {
			return true
		}
		for _, job := range s.state.Jobs {
			if job.BronzeSourceID == item.ID {
				return true
			}
		}
		return false
	}
	published, ok := s.state.Published[item.ID]
	if !ok || !s.deterministicMatchesCurrent(published.Source, item) {
		return !s.hasCurrentJobLocked(item, silverDeterministicRepresentation)
	}
	if s.knowledgeMatchesCurrent(published.Source, item) {
		return false
	}
	return !s.hasCurrentJobLocked(item, silverKnowledgeRepresentation)
}

func deterministicProducer() silverProducer {
	return silverProducer{ProcessorID: silverProcessorID, ProcessorVersion: silverProcessorVersion}
}

func (s *silverService) knowledgeProducer() silverProducer {
	producer := silverProducer{ProcessorID: silverKnowledgeID, ProcessorVersion: silverKnowledgeVersion}
	if s.semantic != nil {
		producer.ModelID, producer.ModelRevision = s.semantic.identity()
	}
	return producer
}

func deterministicInputIdentity(item bronzeItem) string {
	return stableID("source-silver-deterministic-input", struct {
		SourceID, Hash, Title, Mime string
	}{item.ID, item.Hash, item.Title, item.Mime})
}

func (s *silverService) knowledgeInputIdentity(item bronzeItem) string {
	limit := 0
	if s.semantic != nil {
		limit = s.semantic.maximumInputBytes()
	}
	return knowledgeInputIdentity(deterministicInputIdentity(item), limit)
}

func knowledgeInputIdentity(deterministic string, inputLimit int) string {
	return stableID("source-silver-knowledge-input", struct {
		Deterministic string
		InputLimit    int
	}{deterministic, inputLimit})
}

func representationComplete(state string) bool {
	return state == silverRepresentationReady || state == silverRepresentationPartial || state == silverRepresentationSkipped
}

func (s *silverService) deterministicMatchesCurrent(source silverSource, item bronzeItem) bool {
	representation := source.Representations.Deterministic
	return silverSourceMatchesBronze(source, item) && representationComplete(representation.State) &&
		representation.Producer == deterministicProducer() && representation.InputIdentity == deterministicInputIdentity(item)
}

func (s *silverService) knowledgeMatchesCurrent(source silverSource, item bronzeItem) bool {
	deterministic := source.Representations.Deterministic
	knowledge := source.Representations.Knowledge
	if deterministic.State == silverRepresentationSkipped {
		return knowledge.State == silverRepresentationSkipped
	}
	if s.semantic == nil {
		return representationComplete(knowledge.State) ||
			(knowledge.State == silverRepresentationUnavailable && knowledge.Producer == s.knowledgeProducer() &&
				knowledge.InputIdentity == s.knowledgeInputIdentity(item))
	}
	return representationComplete(knowledge.State) && knowledge.Producer == s.knowledgeProducer() &&
		knowledge.InputIdentity == s.knowledgeInputIdentity(item)
}

func silverSourceMatchesBronze(source silverSource, item bronzeItem) bool {
	return source.BronzeSourceID == item.ID && source.BronzeContentSHA256 == item.Hash &&
		source.Title == item.Title && source.Mime == item.Mime
}

func (s *silverService) jobMatchesItem(job silverProcessorJob, item bronzeItem, representation string) bool {
	if job.BronzeSourceID != item.ID || job.BronzeContentSHA256 != item.Hash || job.Title != item.Title ||
		job.Mime != item.Mime || job.Representation != representation {
		return false
	}
	if representation == silverDeterministicRepresentation {
		return job.Producer == deterministicProducer() && job.InputIdentity == deterministicInputIdentity(item)
	}
	return job.Producer == s.knowledgeProducer() && job.InputIdentity == s.knowledgeInputIdentity(item)
}

func (s *silverService) hasCurrentJobLocked(item bronzeItem, representation string) bool {
	for _, job := range s.state.Jobs {
		if job.State != "cancelled" && s.jobMatchesItem(job, item, representation) {
			return true
		}
	}
	return false
}

func (s *silverService) enqueue(item bronzeItem) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	changed := false
	for index := range s.state.Jobs {
		job := &s.state.Jobs[index]
		if job.BronzeSourceID == item.ID && !s.jobMatchesItem(*job, item, job.Representation) && (job.State == "queued" || job.State == "running" || job.State == "failed") {
			job.State = "cancelled"
			job.Checkpoints = nil
			job.UpdatedAt = time.Now().UnixMilli()
			changed = true
		}
	}
	if item.Deleted {
		dataChanged := false
		kept := s.state.Jobs[:0]
		for _, job := range s.state.Jobs {
			if job.BronzeSourceID != item.ID {
				kept = append(kept, job)
			} else {
				changed = true
			}
		}
		s.state.Jobs = kept
		if _, ok := s.state.Published[item.ID]; ok {
			delete(s.state.Published, item.ID)
			changed = true
			dataChanged = true
		}
		if changed {
			s.state.Revision++
			if dataChanged {
				s.state.DataRevision++
			}
			if err := s.saveLocked(); err != nil {
				return false, err
			}
		}
		return changed, nil
	}
	published, publishedOK := s.state.Published[item.ID]
	deterministicCurrent := publishedOK && s.deterministicMatchesCurrent(published.Source, item)
	knowledgeCurrent := deterministicCurrent && s.knowledgeMatchesCurrent(published.Source, item)
	if deterministicCurrent && knowledgeCurrent {
		if changed {
			s.state.Revision++
			return true, s.saveLocked()
		}
		return false, nil
	}
	representation := silverDeterministicRepresentation
	producer := deterministicProducer()
	inputIdentity := deterministicInputIdentity(item)
	if deterministicCurrent {
		representation = silverKnowledgeRepresentation
		if s.semantic == nil {
			if !representationComplete(published.Source.Representations.Knowledge.State) {
				deterministic := published.Observations[:0]
				for _, observation := range published.Observations {
					if observation.Producer.ProcessorID == silverExtractionID {
						deterministic = append(deterministic, observation)
					}
				}
				published.Observations = deterministic
				published.Entities = nil
				published.Claims = nil
				published.Source.Representations.Knowledge = silverRepresentation{
					State: silverRepresentationUnavailable, Producer: s.knowledgeProducer(),
					InputIdentity: s.knowledgeInputIdentity(item), Error: "semantic model is not configured",
				}
				published.PublishedAt = time.Now().UnixMilli()
				refreshSilverSourceIDs(&published)
				s.state.Published[item.ID] = published
				s.state.DataRevision++
				changed = true
			}
			if changed {
				s.state.Revision++
				return true, s.saveLocked()
			}
			return false, nil
		}
		producer = s.knowledgeProducer()
		inputIdentity = s.knowledgeInputIdentity(item)
	}
	for index := range s.state.Jobs {
		job := &s.state.Jobs[index]
		if job.State != "cancelled" && s.jobMatchesItem(*job, item, representation) {
			if changed {
				s.state.Revision++
				if err := s.saveLocked(); err != nil {
					return false, err
				}
				if job.State == "queued" {
					s.signal()
				}
				return true, nil
			}
			return false, nil
		}
	}
	s.state.NextJob++
	now := time.Now().UnixMilli()
	s.state.Jobs = append(s.state.Jobs, silverProcessorJob{
		ID: fmt.Sprintf("job-%d", s.state.NextJob), BronzeSourceID: item.ID, BronzeContentSHA256: item.Hash,
		Title: item.Title, Mime: item.Mime, Representation: representation, Producer: producer,
		InputIdentity: inputIdentity, State: "queued", AcceptedAt: now, UpdatedAt: now,
	})
	s.state.Revision++
	if err := s.saveLocked(); err != nil {
		return false, err
	}
	s.signal()
	return true, nil
}

func (s *silverService) processNext(ctx context.Context) bool {
	for {
		processed, followUp := s.processOne(ctx)
		if !processed || !followUp {
			return processed
		}
	}
}

func (s *silverService) processOne(ctx context.Context) (bool, bool) {
	s.mu.Lock()
	if s.pendingPersistence {
		if err := s.persistCurrentLocked(); err != nil {
			s.statusError = "Silver pending job state storage failed: " + err.Error()
			s.mu.Unlock()
			return false, false
		}
		s.pendingPersistence = false
	}
	index := -1
	for i := range s.state.Jobs {
		if s.state.Jobs[i].State == "queued" {
			index = i
			break
		}
	}
	if index < 0 {
		s.mu.Unlock()
		return false, false
	}
	s.state.Jobs[index].State = "running"
	s.state.Jobs[index].UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	job := s.state.Jobs[index]
	if err := s.saveLocked(); err != nil {
		s.statusError = "Silver job state storage failed: " + err.Error()
		s.mu.Unlock()
		return false, false
	}
	s.mu.Unlock()

	err := s.processJob(ctx, job.ID)
	if err != nil && !errors.Is(err, context.Canceled) {
		s.mu.Lock()
		if current := s.jobLocked(job.ID); current != nil && current.State == "running" {
			retryable := silverProcessErrorIsRetryable(err)
			current.State = "failed"
			current.Error = err.Error()
			current.Retryable = retryable
			current.Attempts++
			if retryable {
				current.RetryAt = time.Now().Add(silverRetryDelay(current.Attempts)).UnixMilli()
			} else {
				current.RetryAt = 0
			}
			current.UpdatedAt = time.Now().UnixMilli()
			if dataset, ok := s.state.Published[current.BronzeSourceID]; ok && current.Representation == silverKnowledgeRepresentation {
				dataset.Source.Representations.Knowledge.State = silverRepresentationFailed
				dataset.Source.Representations.Knowledge.Error = truncate(err.Error(), 1000)
				s.state.Published[current.BronzeSourceID] = dataset
				s.state.DataRevision++
			}
			s.state.Revision++
			if saveErr := s.saveFailureLocked(); saveErr != nil {
				s.statusError = "Silver failed-state storage failed: " + saveErr.Error()
				log.Printf("Silver failed-state storage failed for %s: %v", job.ID, saveErr)
			}
		}
		s.mu.Unlock()
	}
	if err == nil && job.Representation == silverDeterministicRepresentation {
		if reconcileErr := s.reconcile(); reconcileErr != nil {
			return false, false
		}
		s.mu.Lock()
		knowledgeQueued := false
		for _, candidate := range s.state.Jobs {
			if candidate.BronzeSourceID == job.BronzeSourceID && candidate.Representation == silverKnowledgeRepresentation && candidate.State == "queued" {
				knowledgeQueued = true
				break
			}
		}
		s.mu.Unlock()
		return ctx.Err() == nil, knowledgeQueued
	}
	return ctx.Err() == nil, false
}

func silverRetryDelay(attempt int) time.Duration {
	if attempt < 1 {
		attempt = 1
	}
	delay := time.Second << min(attempt-1, 8)
	return min(delay, 5*time.Minute)
}

func (s *silverService) processJob(ctx context.Context, jobID string) error {
	s.mu.Lock()
	job := s.jobLocked(jobID)
	if job == nil {
		s.mu.Unlock()
		return errors.New("Silver job missing")
	}
	copy := *job
	s.mu.Unlock()

	item, err := s.bronze.load(copy.BronzeSourceID)
	if err != nil || item.Deleted {
		if err == nil {
			err = os.ErrNotExist
		}
		return permanentSilverProcessError(fmt.Errorf("Bronze content is missing: %w", err))
	}
	if item.Hash != copy.BronzeContentSHA256 || item.Title != copy.Title || item.Mime != copy.Mime {
		return s.cancelJob(jobID)
	}
	if !s.jobMatchesItem(copy, item, copy.Representation) {
		return s.cancelJob(jobID)
	}
	switch copy.Representation {
	case silverDeterministicRepresentation:
		return s.processDeterministicJob(jobID, item)
	case silverKnowledgeRepresentation:
		return s.processKnowledgeJob(ctx, jobID, item, copy)
	default:
		return permanentSilverProcessError(fmt.Errorf("unknown Silver representation %q", copy.Representation))
	}
}

func (s *silverService) processDeterministicJob(jobID string, item bronzeItem) error {
	item, file, err := s.bronze.openContent(item.ID)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return permanentSilverProcessError(fmt.Errorf("Bronze content is missing: %w", err))
		}
		return err
	}
	parsed, parseErr := parseSilverReader(item, file)
	closeErr := file.Close()
	if parseErr != nil {
		return parseErr
	}
	if closeErr != nil {
		return closeErr
	}
	return s.publishDeterministic(jobID, item, parsed)
}

func (s *silverService) processKnowledgeJob(ctx context.Context, jobID string, item bronzeItem, snapshot silverProcessorJob) error {
	if s.semantic == nil {
		return s.cancelJob(jobID)
	}
	s.mu.Lock()
	dataset, ok := s.state.Published[item.ID]
	if !ok || !s.deterministicMatchesCurrent(dataset.Source, item) ||
		dataset.Source.Representations.Deterministic.State != silverRepresentationReady {
		s.mu.Unlock()
		return s.cancelJob(jobID)
	}
	dataset.Source.Representations.Knowledge = silverRepresentation{
		State: silverRepresentationProcessing, Producer: snapshot.Producer, InputIdentity: snapshot.InputIdentity,
	}
	s.state.Published[item.ID] = dataset
	s.state.Revision++
	s.state.DataRevision++
	if err := s.saveLocked(); err != nil {
		s.mu.Unlock()
		return err
	}
	s.mu.Unlock()

	fragments, err := parsedFragmentsFromDataset(dataset)
	if err != nil {
		return err
	}

	s.mu.Lock()
	job := s.jobLocked(jobID)
	if job == nil || job.State != "running" {
		s.mu.Unlock()
		return context.Canceled
	}
	batches, err := buildSilverBatches(snapshot, fragments, s.configuration.SemanticBatchTargetBytes, s.semantic)
	if err != nil {
		s.mu.Unlock()
		return err
	}
	job.TotalBatches = len(batches)
	job.Checkpoints = checkpointsMatchingBatches(job.Checkpoints, batches)
	job.UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	if err := s.saveLocked(); err != nil {
		s.mu.Unlock()
		return err
	}
	s.mu.Unlock()

	for batchIndex, batch := range batches {
		if err := ctx.Err(); err != nil {
			return err
		}
		s.mu.Lock()
		job = s.jobLocked(jobID)
		if job == nil || job.State != "running" {
			s.mu.Unlock()
			return context.Canceled
		}
		if checkpointFor(job, batchIndex, batch.Hash) != nil {
			s.mu.Unlock()
			continue
		}
		s.mu.Unlock()

		checkpoint, err := extractSilverBatch(ctx, snapshot, batch.Fragments, batchIndex, batch.Hash, s.semantic)
		if err != nil {
			return err
		}
		s.mu.Lock()
		job = s.jobLocked(jobID)
		if job == nil || job.State != "running" {
			s.mu.Unlock()
			return context.Canceled
		}
		job.Checkpoints = replaceCheckpoint(job.Checkpoints, checkpoint)
		job.UpdatedAt = time.Now().UnixMilli()
		s.state.Revision++
		if err := s.saveLocked(); err != nil {
			s.mu.Unlock()
			return err
		}
		s.mu.Unlock()
		if s.afterCheckpoint != nil {
			s.afterCheckpoint(jobID, batchIndex)
		}
	}
	return s.publishKnowledge(jobID, item)
}

func (s *silverService) publishDeterministic(jobID string, current bronzeItem, parsed silverParseResult) error {
	jobSnapshot := silverProcessorJob{}
	s.mu.Lock()
	job := s.jobLocked(jobID)
	if job == nil || job.State != "running" {
		s.mu.Unlock()
		return context.Canceled
	}
	latest, err := s.bronze.load(current.ID)
	if err != nil {
		s.mu.Unlock()
		return fmt.Errorf("load current Bronze metadata: %w", err)
	}
	if latest.Deleted || !silverSourceMatchesBronze(silverSource{
		BronzeSourceID: job.BronzeSourceID, BronzeContentSHA256: job.BronzeContentSHA256,
		Title: job.Title, Mime: job.Mime,
	}, latest) {
		err := s.cancelJobLocked(job)
		s.mu.Unlock()
		return err
	}
	jobSnapshot = *job
	dataset, err := deterministicSilverDataset(jobSnapshot, parsed, s.semantic)
	if err != nil {
		s.mu.Unlock()
		return err
	}
	s.state.Published[job.BronzeSourceID] = dataset
	job.State = "completed"
	job.Error = ""
	job.RetryAt = 0
	job.Retryable = false
	job.UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	s.state.DataRevision++
	err = s.saveLocked()
	s.mu.Unlock()
	if err == nil && s.onDeterministicPublish != nil {
		s.onDeterministicPublish()
	}
	if err == nil {
		s.signal()
	}
	return err
}

func deterministicSilverDataset(job silverProcessorJob, parsed silverParseResult, model semanticModel) (silverDataset, error) {
	producer := silverProducer{ProcessorID: silverExtractionID, ProcessorVersion: silverExtractionVersion}
	dataset := silverDataset{Source: silverSource{
		BronzeSourceID: job.BronzeSourceID, BronzeContentSHA256: job.BronzeContentSHA256,
		Title: job.Title, Mime: job.Mime,
		Representations: silverRepresentations{Deterministic: silverRepresentation{
			State: parsed.State, Producer: job.Producer, InputIdentity: job.InputIdentity, Error: parsed.Error,
		}},
	}, PublishedAt: time.Now().UnixMilli()}
	knowledgeProducer := silverProducer{ProcessorID: silverKnowledgeID, ProcessorVersion: silverKnowledgeVersion}
	knowledgeLimit := 0
	if model != nil {
		knowledgeProducer.ModelID, knowledgeProducer.ModelRevision = model.identity()
		knowledgeLimit = model.maximumInputBytes()
	}
	knowledgeInput := knowledgeInputIdentity(job.InputIdentity, knowledgeLimit)
	if parsed.State != silverRepresentationReady {
		dataset.Source.Representations.Knowledge = silverRepresentation{State: silverRepresentationSkipped,
			Producer: knowledgeProducer, InputIdentity: knowledgeInput, Error: parsed.Error}
	} else if model == nil {
		dataset.Source.Representations.Knowledge = silverRepresentation{State: silverRepresentationUnavailable,
			Producer: knowledgeProducer, InputIdentity: knowledgeInput,
			Error: "semantic model is not configured"}
	} else {
		dataset.Source.Representations.Knowledge = silverRepresentation{State: silverRepresentationUnavailable,
			Producer: knowledgeProducer, InputIdentity: knowledgeInput,
			Error: "knowledge has not been processed"}
	}
	for _, fragment := range parsed.Fragments {
		evidence := silverEvidenceForFragment(job, fragment)
		payload, err := json.Marshal(fragment.Payload)
		if err != nil {
			return silverDataset{}, err
		}
		observation := silverObservation{
			Kind: fragment.Kind, Payload: payload, EvidenceIDs: []string{evidence.ID}, Text: fragment.Text,
			Context: fragment.Context, StructuralOnly: fragment.StructuralOnly, Producer: producer,
		}
		observation.ID = observationID(observation)
		dataset.Evidence = append(dataset.Evidence, evidence)
		dataset.Observations = append(dataset.Observations, observation)
	}
	refreshSilverSourceIDs(&dataset)
	return dataset, nil
}

func (s *silverService) publishKnowledge(jobID string, current bronzeItem) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	job := s.jobLocked(jobID)
	if job == nil || job.State != "running" {
		return context.Canceled
	}
	latest, err := s.bronze.load(current.ID)
	if err != nil {
		return fmt.Errorf("load current Bronze metadata: %w", err)
	}
	if latest.Deleted || !s.jobMatchesItem(*job, latest, silverKnowledgeRepresentation) {
		return s.cancelJobLocked(job)
	}
	if len(job.Checkpoints) != job.TotalBatches {
		return errors.New("Silver knowledge job is missing checkpoints")
	}
	dataset, ok := s.state.Published[job.BronzeSourceID]
	if !ok || !s.deterministicMatchesCurrent(dataset.Source, latest) {
		return errors.New("deterministic Silver is missing for knowledge publication")
	}
	deterministic := dataset.Observations[:0]
	for _, observation := range dataset.Observations {
		if observation.Producer.ProcessorID == silverExtractionID {
			deterministic = append(deterministic, observation)
		}
	}
	dataset.Observations = deterministic
	dataset.Entities = nil
	dataset.Claims = nil
	sort.Slice(job.Checkpoints, func(i, j int) bool { return job.Checkpoints[i].BatchIndex < job.Checkpoints[j].BatchIndex })
	entitySeen := map[string]bool{}
	for _, checkpoint := range job.Checkpoints {
		for _, observation := range checkpoint.Observations {
			if observation.Producer.ProcessorID == semanticProcessorID {
				dataset.Observations = append(dataset.Observations, observation)
			}
		}
		resolveCheckpointCandidates(&dataset, checkpoint, entitySeen, job.Producer.ModelID, job.Producer.ModelRevision)
	}
	dataset.Source.Representations.Knowledge = knowledgeRepresentationForCheckpoints(*job)
	dataset.PublishedAt = time.Now().UnixMilli()
	refreshSilverSourceIDs(&dataset)
	s.state.Published[job.BronzeSourceID] = dataset
	job.State = "completed"
	job.Checkpoints = nil
	job.Error = ""
	job.Attempts = 0
	job.RetryAt = 0
	job.Retryable = false
	job.UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	s.state.DataRevision++
	return s.saveLocked()
}

func refreshSilverSourceIDs(dataset *silverDataset) {
	dataset.Source.EvidenceIDs = dataset.Source.EvidenceIDs[:0]
	dataset.Source.ObservationIDs = dataset.Source.ObservationIDs[:0]
	dataset.Source.EntityIDs = dataset.Source.EntityIDs[:0]
	dataset.Source.ClaimIDs = dataset.Source.ClaimIDs[:0]
	for _, evidence := range dataset.Evidence {
		dataset.Source.EvidenceIDs = append(dataset.Source.EvidenceIDs, evidence.ID)
	}
	for _, observation := range dataset.Observations {
		dataset.Source.ObservationIDs = append(dataset.Source.ObservationIDs, observation.ID)
	}
	for _, entity := range dataset.Entities {
		dataset.Source.EntityIDs = append(dataset.Source.EntityIDs, entity.ID)
	}
	for _, claim := range dataset.Claims {
		dataset.Source.ClaimIDs = append(dataset.Source.ClaimIDs, claim.ID)
	}
}

func knowledgeRepresentationForCheckpoints(job silverProcessorJob) silverRepresentation {
	semanticFragments := 0
	completedFragments := 0
	reasons := map[string]bool{}
	for _, checkpoint := range job.Checkpoints {
		semanticFragments += checkpoint.SemanticFragments
		completedFragments += checkpoint.SemanticCompletedFragments
		if checkpoint.SkipReason != "" {
			reasons[checkpoint.SkipReason] = true
		}
	}
	state := silverRepresentationReady
	switch {
	case completedFragments == semanticFragments:
		state = silverRepresentationReady
	case completedFragments == 0:
		state = silverRepresentationSkipped
	default:
		state = silverRepresentationPartial
	}
	errorText := ""
	if len(reasons) > 0 {
		values := make([]string, 0, len(reasons))
		for reason := range reasons {
			values = append(values, reason)
		}
		sort.Strings(values)
		errorText = strings.Join(values, ",")
	}
	return silverRepresentation{
		State: state, Producer: job.Producer, InputIdentity: job.InputIdentity, Error: errorText,
	}
}

func (s *silverService) cancelJob(jobID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	job := s.jobLocked(jobID)
	if job == nil {
		return nil
	}
	return s.cancelJobLocked(job)
}

func (s *silverService) cancelJobLocked(job *silverProcessorJob) error {
	job.State = "cancelled"
	job.Checkpoints = nil
	job.Error = ""
	job.RetryAt = 0
	job.Retryable = false
	job.UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	return s.saveLocked()
}

func (s *silverService) jobLocked(id string) *silverProcessorJob {
	for index := range s.state.Jobs {
		if s.state.Jobs[index].ID == id {
			return &s.state.Jobs[index]
		}
	}
	return nil
}

func checkpointFor(job *silverProcessorJob, index int, hash string) *silverCheckpoint {
	for checkpointIndex := range job.Checkpoints {
		checkpoint := &job.Checkpoints[checkpointIndex]
		if checkpoint.BatchIndex == index && checkpoint.BatchSHA256 == hash {
			return checkpoint
		}
	}
	return nil
}

func replaceCheckpoint(checkpoints []silverCheckpoint, next silverCheckpoint) []silverCheckpoint {
	for index := range checkpoints {
		if checkpoints[index].BatchIndex == next.BatchIndex {
			checkpoints[index] = next
			return checkpoints
		}
	}
	return append(checkpoints, next)
}

func (s *silverService) saveLocked() error {
	wasPending := s.pendingPersistence
	if err := s.persistCurrentLocked(); err != nil {
		if !wasPending {
			s.restoreLocked()
		}
		return err
	}
	s.pendingPersistence = false
	return nil
}

func (s *silverService) saveFailureLocked() error {
	if err := s.persistCurrentLocked(); err != nil {
		s.pendingPersistence = true
		return err
	}
	s.pendingPersistence = false
	return nil
}

func (s *silverService) persistCurrentLocked() error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0700); err != nil {
		return err
	}
	value, err := json.Marshal(s.state)
	if err != nil {
		return err
	}
	if err := savePrivate(s.path, value); err != nil {
		return err
	}
	s.persisted = value
	return nil
}

func (s *silverService) restoreLocked() {
	var last silverDiskState
	if err := json.Unmarshal(s.persisted, &last); err != nil {
		panic("invalid last persisted Silver state: " + err.Error())
	}
	s.state = last
}

func (s *silverService) snapshot() silverSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	snapshot := silverSnapshot{SchemaVersion: silverSnapshotSchemaVersion, Revision: s.state.DataRevision, Error: s.statusError}
	entityMap := map[string]silverEntity{}
	claimMap := map[string]silverClaim{}
	evidenceMap := map[string]silverEvidence{}
	observationMap := map[string]silverObservation{}
	ids := make([]string, 0, len(s.state.Published))
	for id := range s.state.Published {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		dataset := s.state.Published[id]
		current, err := s.bronze.load(id)
		if err != nil {
			s.statusError = "Silver snapshot validation failed: " + err.Error()
			snapshot.Error = s.statusError
			continue
		}
		if current.Deleted || !silverSourceMatchesBronze(dataset.Source, current) {
			continue
		}
		dataset.Source.Stale = !s.deterministicMatchesCurrent(dataset.Source, current)
		snapshot.Sources = append(snapshot.Sources, dataset.Source)
		for _, value := range dataset.Evidence {
			evidenceMap[value.ID] = value
		}
		for _, value := range dataset.Observations {
			observationMap[value.ID] = value
		}
		for _, value := range dataset.Entities {
			entityMap[value.ID] = value
		}
		for _, value := range dataset.Claims {
			claimMap[value.ID] = value
		}
	}
	for _, job := range s.state.Jobs {
		if job.State == "completed" || job.State == "cancelled" {
			continue
		}
		snapshot.Processing = append(snapshot.Processing, silverProcessing{
			BronzeSourceID: job.BronzeSourceID, Representation: job.Representation, State: job.State,
			CompletedBatches: len(job.Checkpoints), TotalBatches: job.TotalBatches,
			Error: job.Error, Retryable: job.Retryable,
		})
	}
	appendSortedValues(evidenceMap, &snapshot.Evidence)
	appendSortedValues(observationMap, &snapshot.Observations)
	appendSortedValues(entityMap, &snapshot.Entities)
	appendSortedValues(claimMap, &snapshot.Claims)
	sort.Slice(snapshot.Processing, func(i, j int) bool {
		return snapshot.Processing[i].BronzeSourceID < snapshot.Processing[j].BronzeSourceID
	})
	return snapshot
}

// deterministicSnapshot is the processor-facing Core Silver view. Downstream
// representations must not need to inspect or wait for semantic knowledge.
func (s *silverService) deterministicSnapshot() silverSnapshot {
	snapshot := s.snapshot()
	deterministicIDs := make(map[string]bool, len(snapshot.Observations))
	observations := snapshot.Observations[:0]
	for _, observation := range snapshot.Observations {
		if observation.Producer.ProcessorID != silverExtractionID {
			continue
		}
		deterministicIDs[observation.ID] = true
		observations = append(observations, observation)
	}
	snapshot.Observations = observations
	snapshot.Entities = nil
	snapshot.Claims = nil
	for index := range snapshot.Sources {
		source := &snapshot.Sources[index]
		ids := make([]string, 0, len(source.ObservationIDs))
		for _, id := range source.ObservationIDs {
			if deterministicIDs[id] {
				ids = append(ids, id)
			}
		}
		source.ObservationIDs = ids
		source.EntityIDs = nil
		source.ClaimIDs = nil
	}
	return snapshot
}

func (s *silverService) refreshStatus() (int64, sourceJobSnapshot, []silverProcessing, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state.DataRevision, s.jobSnapshotLocked(), s.processingSnapshotLocked(), s.statusError
}

func (s *silverService) processingSnapshotLocked() []silverProcessing {
	processing := []silverProcessing{}
	for _, job := range s.state.Jobs {
		if job.State == "completed" || job.State == "cancelled" {
			continue
		}
		processing = append(processing, silverProcessing{
			BronzeSourceID: job.BronzeSourceID, Representation: job.Representation, State: job.State,
			CompletedBatches: len(job.Checkpoints), TotalBatches: job.TotalBatches,
			Error: job.Error, Retryable: job.Retryable,
		})
	}
	sort.Slice(processing, func(i, j int) bool {
		return processing[i].BronzeSourceID < processing[j].BronzeSourceID
	})
	return processing
}

func (s *silverService) jobSnapshot() sourceJobSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.jobSnapshotLocked()
}

func (s *silverService) jobSnapshotLocked() sourceJobSnapshot {
	snapshot := sourceJobSnapshot{Revision: s.state.Revision}
	for _, job := range s.state.Jobs {
		visible := sourceJob{
			ID: job.ID, Kind: "silver_extraction", Title: job.Title, BronzeSourceID: job.BronzeSourceID,
			State: job.State, QueuedAt: job.AcceptedAt,
		}
		switch job.State {
		case "completed":
			visible.CompletedAt = job.UpdatedAt
			snapshot.Completed = append(snapshot.Completed, visible)
		case "cancelled":
			continue
		default:
			snapshot.Queued = append(snapshot.Queued, visible)
		}
	}
	return snapshot
}

func appendSortedValues[T any](values map[string]T, destination *[]T) {
	ids := make([]string, 0, len(values))
	for id := range values {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		*destination = append(*destination, values[id])
	}
}

func observationID(observation silverObservation) string {
	return stableID("source-silver-observation", struct {
		Kind           string               `json:"kind"`
		Payload        json.RawMessage      `json:"payload"`
		Evidence       []string             `json:"evidence"`
		Text           string               `json:"text,omitempty"`
		Context        *parsedSilverContext `json:"context,omitempty"`
		StructuralOnly bool                 `json:"structural_only,omitempty"`
		Confidence     *float64             `json:"confidence,omitempty"`
		Producer       silverProducer       `json:"producer"`
	}{
		observation.Kind, observation.Payload, observation.EvidenceIDs, observation.Text, observation.Context,
		observation.StructuralOnly, observation.Confidence, observation.Producer,
	})
}

func stableID(prefix string, value any) string {
	encoded, _ := json.Marshal(value)
	sum := sha256.Sum256(append(append([]byte(prefix), 0), encoded...))
	return hex.EncodeToString(sum[:])
}

func hashBytes(value []byte) string { sum := sha256.Sum256(value); return hex.EncodeToString(sum[:]) }

func scalarText(value any) string {
	switch typed := value.(type) {
	case string:
		return typed
	case json.Number:
		return typed.String()
	case bool:
		return strconv.FormatBool(typed)
	default:
		return ""
	}
}

func truncate(value string, limit int) string {
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	return string(runes[:limit]) + "…"
}
