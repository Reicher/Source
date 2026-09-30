package main

import (
	"bytes"
	"context"
	"crypto/rand"
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
	silverSchemaVersion     = 1
	silverProcessorID       = "source.silver.deterministic-ingestion"
	silverExtractionID      = "source.silver.format-extraction"
	silverExtractionVersion = "1"
	silverResolverID        = "source.silver.candidate-resolver"
	silverResolverVersion   = "2"
	silverProcessorVersion  = "1-" + silverExtractionVersion
	silverKnowledgeID       = "source.silver.knowledge"
	silverKnowledgeVersion  = "1-" + semanticProcessorVersion + "-" + silverResolverVersion
	silverJobProcessorID    = "source.silver.processing"
	silverJobVersion        = silverProcessorVersion + "-" + silverKnowledgeVersion
	silverResolutionMinimum = 0.70
	silverMaximumBatchBytes = 4096
	silverInspectionBytes   = 8192
	silverMaximumInputBytes = 8 * 1024 * 1024
	silverReconcileInterval = time.Second

	silverExtractionCompleted = "completed"
	silverExtractionSkipped   = "skipped"
	silverSemanticCompleted   = "completed"
	silverSemanticSkipped     = "skipped"
	silverSemanticPartial     = "partial"

	silverRepresentationReady       = "ready"
	silverRepresentationProcessing  = "processing"
	silverRepresentationUnavailable = "unavailable"
	silverRepresentationSkipped     = "skipped"
	silverRepresentationPartial     = "partial"
	silverRepresentationFailed      = "failed"

	silverSkipUnsupportedContent = "unsupported_content"
	silverSkipSourceTooLarge     = "source_too_large"
	silverSkipModelUnavailable   = "model_unavailable"
	silverSkipFragmentTooLarge   = "fragment_exceeds_model_limit"
	silverSkipModelContract      = "model_contract_failure"
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
	ID          string          `json:"id"`
	Kind        string          `json:"kind"`
	Payload     json.RawMessage `json:"payload"`
	EvidenceIDs []string        `json:"evidence_ids"`
	Confidence  *float64        `json:"confidence,omitempty"`
	Producer    silverProducer  `json:"producer"`
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

type silverCoverage struct {
	ExtractionState    string `json:"extraction_state"`
	SemanticState      string `json:"semantic_state"`
	SemanticSkipReason string `json:"semantic_skip_reason,omitempty"`
}

type silverRepresentation struct {
	State    string         `json:"state"`
	Producer silverProducer `json:"producer"`
	Error    string         `json:"error,omitempty"`
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
	ProcessorID         string                `json:"processor_id"`
	ProcessorVersion    string                `json:"processor_version"`
	ModelID             string                `json:"model_id,omitempty"`
	ModelRevision       string                `json:"model_revision,omitempty"`
	SemanticInputLimit  int                   `json:"semantic_input_limit,omitempty"`
	Coverage            silverCoverage        `json:"coverage"`
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
	Representation   string `json:"representation,omitempty"`
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
	SemanticSkipReason         string                        `json:"semantic_skip_reason,omitempty"`
}

type silverJob struct {
	ID                  string             `json:"id"`
	BronzeSourceID      string             `json:"bronze_source_id"`
	BronzeContentSHA256 string             `json:"bronze_content_sha256"`
	Title               string             `json:"title"`
	Mime                string             `json:"mime"`
	ProcessorID         string             `json:"processor_id"`
	ProcessorVersion    string             `json:"processor_version"`
	ModelID             string             `json:"model_id,omitempty"`
	ModelRevision       string             `json:"model_revision,omitempty"`
	SemanticInputLimit  int                `json:"semantic_input_limit,omitempty"`
	Coverage            silverCoverage     `json:"coverage"`
	Phase               string             `json:"phase,omitempty"`
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

type silverEntityRecord struct {
	Entity        silverEntity `json:"entity"`
	Label         string       `json:"label"`
	Normalized    string       `json:"normalized"`
	Type          string       `json:"type,omitempty"`
	TypeAmbiguous bool         `json:"type_ambiguous,omitempty"`
}

type silverDiskState struct {
	SchemaVersion int                           `json:"schema_version"`
	Revision      int64                         `json:"revision"`
	DataRevision  int64                         `json:"data_revision"`
	RevisionModel int                           `json:"revision_model"`
	NextJob       int64                         `json:"next_job"`
	Jobs          []silverJob                   `json:"jobs"`
	Published     map[string]silverDataset      `json:"published"`
	History       map[string][]silverDataset    `json:"history"`
	Entities      map[string]silverEntityRecord `json:"entities"`
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
	value, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		s.state = silverDiskState{SchemaVersion: silverSchemaVersion, RevisionModel: 1, Published: map[string]silverDataset{}, History: map[string][]silverDataset{}, Entities: map[string]silverEntityRecord{}}
	} else if err != nil {
		return nil, err
	} else if err := json.Unmarshal(value, &s.state); err != nil {
		return nil, fmt.Errorf("invalid Silver state: %w", err)
	}
	if s.state.SchemaVersion != silverSchemaVersion {
		return nil, fmt.Errorf("unsupported Silver state version %d", s.state.SchemaVersion)
	}
	if s.state.Published == nil {
		s.state.Published = map[string]silverDataset{}
	}
	if s.state.History == nil {
		s.state.History = map[string][]silverDataset{}
	}
	if s.state.Entities == nil {
		s.state.Entities = map[string]silverEntityRecord{}
	}
	migratedRevision := false
	if s.state.RevisionModel == 0 {
		s.state.DataRevision = s.state.Revision
		s.state.RevisionModel = 1
		migratedRevision = true
	} else if s.state.RevisionModel != 1 {
		return nil, fmt.Errorf("unsupported Silver revision model %d", s.state.RevisionModel)
	}
	s.backfillEntityTypesLocked()
	s.persisted, err = json.Marshal(s.state)
	if err != nil {
		return nil, err
	}
	if migratedRevision {
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

func (s *silverService) modelIdentity() (string, string, int) {
	if s.semantic == nil {
		return "", "", 0
	}
	modelID, modelRevision := s.semantic.identity()
	return modelID, modelRevision, s.semantic.maximumInputBytes()
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
		if _, ok := s.state.History[item.ID]; ok {
			return true
		}
		for _, job := range s.state.Jobs {
			if job.BronzeSourceID == item.ID {
				return true
			}
		}
		return false
	}
	modelID, modelRevision, semanticInputLimit := s.modelIdentity()
	if published, ok := s.state.Published[item.ID]; ok && s.sourceMatchesCurrent(published.Source, item) &&
		s.knowledgeMatchesCurrent(published.Source, modelID, modelRevision, semanticInputLimit) {
		return false
	}
	for _, job := range s.state.Jobs {
		if job.State != "cancelled" && silverJobMatchesItem(job, item, modelID, modelRevision, semanticInputLimit) {
			return false
		}
	}
	return true
}

func silverJobMatchesItem(job silverJob, item bronzeItem, modelID, modelRevision string, semanticInputLimit int) bool {
	return job.BronzeSourceID == item.ID && job.BronzeContentSHA256 == item.Hash &&
		job.Title == item.Title && job.Mime == item.Mime &&
		job.ProcessorID == silverJobProcessorID && job.ProcessorVersion == silverJobVersion &&
		job.ModelID == modelID && job.ModelRevision == modelRevision && job.SemanticInputLimit == semanticInputLimit
}

func (s *silverService) sourceMatchesCurrent(source silverSource, item bronzeItem) bool {
	return silverSourceMatchesBronze(source, item) && source.ProcessorID == silverProcessorID &&
		source.ProcessorVersion == silverProcessorVersion
}

func (s *silverService) knowledgeMatchesCurrent(source silverSource, modelID, modelRevision string, semanticInputLimit int) bool {
	if source.Coverage.ExtractionState != silverExtractionCompleted {
		return true
	}
	if s.semantic == nil {
		return source.ModelID != "" || source.Representations.Knowledge.State == silverRepresentationUnavailable ||
			source.Coverage.SemanticSkipReason == silverSkipModelUnavailable
	}
	producer := source.Representations.Knowledge.Producer
	state := source.Representations.Knowledge.State
	return source.ModelID == modelID && source.ModelRevision == modelRevision &&
		source.SemanticInputLimit == semanticInputLimit && producer.ProcessorID == silverKnowledgeID &&
		producer.ProcessorVersion == silverKnowledgeVersion && producer.ModelID == modelID && producer.ModelRevision == modelRevision &&
		(state == silverRepresentationReady || state == silverRepresentationPartial || state == silverRepresentationSkipped)
}

func silverSourceMatchesBronze(source silverSource, item bronzeItem) bool {
	return source.BronzeSourceID == item.ID && source.BronzeContentSHA256 == item.Hash &&
		source.Title == item.Title && source.Mime == item.Mime
}

func (s *silverService) enqueue(item bronzeItem) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	modelID, modelRevision, semanticInputLimit := s.modelIdentity()
	changed := false
	for index := range s.state.Jobs {
		job := &s.state.Jobs[index]
		if job.BronzeSourceID == item.ID && !silverJobMatchesItem(*job, item, modelID, modelRevision, semanticInputLimit) && (job.State == "queued" || job.State == "running" || job.State == "failed") {
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
		if _, ok := s.state.History[item.ID]; ok {
			delete(s.state.History, item.ID)
			changed = true
		}
		s.pruneEntitiesLocked()
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
	if published, ok := s.state.Published[item.ID]; ok && s.sourceMatchesCurrent(published.Source, item) &&
		s.knowledgeMatchesCurrent(published.Source, modelID, modelRevision, semanticInputLimit) {
		if changed {
			s.state.Revision++
			return true, s.saveLocked()
		}
		return false, nil
	}
	for index := range s.state.Jobs {
		job := &s.state.Jobs[index]
		if job.State != "cancelled" && silverJobMatchesItem(*job, item, modelID, modelRevision, semanticInputLimit) {
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
	s.state.Jobs = append(s.state.Jobs, silverJob{
		ID: fmt.Sprintf("job-%d", s.state.NextJob), BronzeSourceID: item.ID, BronzeContentSHA256: item.Hash,
		Title: item.Title, Mime: item.Mime, ProcessorID: silverJobProcessorID, ProcessorVersion: silverJobVersion,
		ModelID: modelID, ModelRevision: modelRevision, SemanticInputLimit: semanticInputLimit,
		State: "queued", Phase: s.nextJobPhaseLocked(item), AcceptedAt: now, UpdatedAt: now,
	})
	s.state.Revision++
	if err := s.saveLocked(); err != nil {
		return false, err
	}
	s.signal()
	return true, nil
}

func (s *silverService) nextJobPhaseLocked(item bronzeItem) string {
	if published, ok := s.state.Published[item.ID]; ok && s.sourceMatchesCurrent(published.Source, item) {
		return "knowledge"
	}
	return "deterministic"
}

func (s *silverService) processNext(ctx context.Context) bool {
	s.mu.Lock()
	if s.pendingPersistence {
		if err := s.persistCurrentLocked(); err != nil {
			s.statusError = "Silver pending job state storage failed: " + err.Error()
			s.mu.Unlock()
			return false
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
		return false
	}
	s.state.Jobs[index].State = "running"
	s.state.Jobs[index].UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	job := s.state.Jobs[index]
	if err := s.saveLocked(); err != nil {
		s.statusError = "Silver job state storage failed: " + err.Error()
		s.mu.Unlock()
		return false
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
			if dataset, ok := s.state.Published[current.BronzeSourceID]; ok && current.Phase == "knowledge" {
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
	return ctx.Err() == nil
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

	var parsed silverParseResult
	s.mu.Lock()
	core, coreCurrent := s.state.Published[item.ID]
	coreCurrent = coreCurrent && s.sourceMatchesCurrent(core.Source, item)
	s.mu.Unlock()
	if coreCurrent {
		parsed.Fragments, err = parsedFragmentsFromDataset(core)
		parsed.Coverage = silverCoverage{ExtractionState: core.Source.Coverage.ExtractionState}
		if err == nil && s.semantic != nil && parsed.Coverage.ExtractionState == silverExtractionCompleted {
			err = s.beginKnowledge(jobID)
		}
	} else {
		var file *os.File
		item, file, err = s.bronze.openContent(copy.BronzeSourceID)
		if err == nil {
			parsed, err = parseSilverReader(item, file)
			closeErr := file.Close()
			if err == nil {
				err = closeErr
			}
		}
		if err == nil {
			err = s.publishDeterministic(jobID, item, parsed)
		}
	}
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return permanentSilverProcessError(fmt.Errorf("Bronze content is missing: %w", err))
		}
		return err
	}
	fragments := parsed.Fragments

	if s.semantic == nil || parsed.Coverage.ExtractionState != silverExtractionCompleted {
		return s.completeWithoutKnowledge(jobID)
	}

	s.mu.Lock()
	job = s.jobLocked(jobID)
	if job == nil || job.State != "running" {
		s.mu.Unlock()
		return context.Canceled
	}
	job.Coverage = parsed.Coverage
	job.Phase = "knowledge"
	batches, err := buildSilverBatches(copy, fragments, s.configuration.SemanticBatchTargetBytes, s.semantic)
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

		checkpoint, err := extractSilverBatch(ctx, copy, batch.Fragments, batchIndex, batch.Hash, s.semantic)
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

func (s *silverService) beginKnowledge(jobID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	job := s.jobLocked(jobID)
	if job == nil || job.State != "running" {
		return context.Canceled
	}
	dataset, ok := s.state.Published[job.BronzeSourceID]
	if !ok {
		return errors.New("deterministic Silver is missing for knowledge processing")
	}
	job.Phase = "knowledge"
	dataset.Source.Representations.Knowledge = silverRepresentation{State: silverRepresentationProcessing,
		Producer: silverProducer{ProcessorID: silverKnowledgeID, ProcessorVersion: silverKnowledgeVersion,
			ModelID: job.ModelID, ModelRevision: job.ModelRevision}}
	s.state.Published[job.BronzeSourceID] = dataset
	s.state.Revision++
	s.state.DataRevision++
	return s.saveLocked()
}

func (s *silverService) publishDeterministic(jobID string, current bronzeItem, parsed silverParseResult) error {
	jobSnapshot := silverJob{}
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
	if prior, ok := s.state.Published[job.BronzeSourceID]; ok {
		s.state.History[job.BronzeSourceID] = append(s.state.History[job.BronzeSourceID], prior)
	}
	s.state.Published[job.BronzeSourceID] = dataset
	job.Coverage = dataset.Source.Coverage
	job.Phase = "knowledge"
	job.UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	s.state.DataRevision++
	err = s.saveLocked()
	s.mu.Unlock()
	if err == nil && s.onDeterministicPublish != nil {
		s.onDeterministicPublish()
	}
	return err
}

func deterministicSilverDataset(job silverJob, parsed silverParseResult, model semanticModel) (silverDataset, error) {
	producer := silverProducer{ProcessorID: silverExtractionID, ProcessorVersion: silverExtractionVersion}
	dataset := silverDataset{Source: silverSource{
		BronzeSourceID: job.BronzeSourceID, BronzeContentSHA256: job.BronzeContentSHA256,
		Title: job.Title, Mime: job.Mime, ProcessorID: silverProcessorID, ProcessorVersion: silverProcessorVersion,
		Coverage: parsed.Coverage,
		Representations: silverRepresentations{Deterministic: silverRepresentation{
			State: silverRepresentationReady, Producer: silverProducer{ProcessorID: silverProcessorID, ProcessorVersion: silverProcessorVersion},
		}},
	}, PublishedAt: time.Now().UnixMilli()}
	if parsed.Coverage.ExtractionState != silverExtractionCompleted {
		dataset.Source.Representations.Deterministic.State = silverRepresentationSkipped
		dataset.Source.Representations.Knowledge = silverRepresentation{State: silverRepresentationSkipped,
			Producer: silverProducer{ProcessorID: silverKnowledgeID, ProcessorVersion: silverKnowledgeVersion}}
	} else if model == nil {
		dataset.Source.Coverage.SemanticState = silverSemanticSkipped
		dataset.Source.Coverage.SemanticSkipReason = silverSkipModelUnavailable
		dataset.Source.Representations.Knowledge = silverRepresentation{State: silverRepresentationUnavailable,
			Producer: silverProducer{ProcessorID: silverKnowledgeID, ProcessorVersion: silverKnowledgeVersion},
			Error:    "semantic model is not configured"}
	} else {
		modelID, modelRevision := model.identity()
		dataset.Source.ModelID = modelID
		dataset.Source.ModelRevision = modelRevision
		dataset.Source.SemanticInputLimit = model.maximumInputBytes()
		dataset.Source.Representations.Knowledge = silverRepresentation{State: silverRepresentationProcessing,
			Producer: silverProducer{ProcessorID: silverKnowledgeID, ProcessorVersion: silverKnowledgeVersion,
				ModelID: modelID, ModelRevision: modelRevision}}
	}
	for _, fragment := range parsed.Fragments {
		evidence := silverEvidenceForFragment(job, fragment)
		payload, err := json.Marshal(fragment.Payload)
		if err != nil {
			return silverDataset{}, err
		}
		observation := silverObservation{Kind: fragment.Kind, Payload: payload, EvidenceIDs: []string{evidence.ID}, Producer: producer}
		observation.ID = observationID(observation)
		dataset.Evidence = append(dataset.Evidence, evidence)
		dataset.Observations = append(dataset.Observations, observation)
	}
	refreshSilverSourceIDs(&dataset)
	return dataset, nil
}

func parsedFragmentsFromDataset(dataset silverDataset) ([]parsedSilverFragment, error) {
	evidence := make(map[string]silverEvidence, len(dataset.Evidence))
	for _, value := range dataset.Evidence {
		evidence[value.ID] = value
	}
	fragments := make([]parsedSilverFragment, 0, len(dataset.Observations))
	for _, observation := range dataset.Observations {
		if observation.Producer.ProcessorID != silverExtractionID || len(observation.EvidenceIDs) != 1 {
			continue
		}
		item, ok := evidence[observation.EvidenceIDs[0]]
		if !ok {
			return nil, fmt.Errorf("deterministic observation %s refers to missing evidence", observation.ID)
		}
		decoder := json.NewDecoder(bytes.NewReader(observation.Payload))
		decoder.UseNumber()
		var payload any
		if err := decoder.Decode(&payload); err != nil || requireJSONEOF(decoder) != nil {
			return nil, fmt.Errorf("decode deterministic observation %s", observation.ID)
		}
		text := ""
		if observation.Kind != "parsed-table-header" {
			text = deterministicFragmentText(observation.Kind, payload)
		}
		fragments = append(fragments, parsedSilverFragment{
			Kind: observation.Kind, Selector: item.Selector, Excerpt: item.Excerpt, Payload: payload, Text: text,
		})
	}
	return fragments, nil
}

func deterministicFragmentText(kind string, payload any) string {
	value, ok := payload.(map[string]any)
	if !ok {
		return ""
	}
	switch kind {
	case "text-block", "markdown-heading":
		text, _ := value["text"].(string)
		return text
	case "parsed-json-value":
		return scalarText(value["value"])
	case "parsed-table-row":
		if columns, ok := value["columns"].(map[string]any); ok && len(columns) > 0 {
			keys := make([]string, 0, len(columns))
			for key := range columns {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			parts := make([]string, 0, len(keys))
			for _, key := range keys {
				if field, ok := columns[key].(string); ok {
					parts = append(parts, field)
				}
			}
			return strings.Join(parts, " ")
		}
		if values, ok := value["values"].([]any); ok {
			parts := make([]string, 0, len(values))
			for _, field := range values {
				if text, ok := field.(string); ok {
					parts = append(parts, text)
				}
			}
			return strings.Join(parts, " ")
		}
	}
	return ""
}

func (s *silverService) completeWithoutKnowledge(jobID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	job := s.jobLocked(jobID)
	if job == nil || job.State != "running" {
		return context.Canceled
	}
	job.State = "completed"
	job.Checkpoints = nil
	job.Error = ""
	job.Attempts = 0
	job.RetryAt = 0
	job.Retryable = false
	job.UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	return s.saveLocked()
}

func (s *silverService) publishKnowledge(jobID string, current bronzeItem) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	job := s.jobLocked(jobID)
	if job == nil || job.State != "running" {
		return context.Canceled
	}
	latest, err := s.bronze.load(current.ID)
	modelID, modelRevision, semanticInputLimit := s.modelIdentity()
	if err != nil {
		return fmt.Errorf("load current Bronze metadata: %w", err)
	}
	if latest.Deleted || !silverJobMatchesItem(*job, latest, modelID, modelRevision, semanticInputLimit) {
		return s.cancelJobLocked(job)
	}
	if len(job.Checkpoints) != job.TotalBatches {
		return errors.New("Silver knowledge job is missing checkpoints")
	}
	dataset, ok := s.state.Published[job.BronzeSourceID]
	if !ok || !s.sourceMatchesCurrent(dataset.Source, latest) {
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
		s.resolveCheckpointCandidatesLocked(&dataset, checkpoint, entitySeen, job.ModelID, job.ModelRevision)
	}
	dataset.Source.ModelID = job.ModelID
	dataset.Source.ModelRevision = job.ModelRevision
	dataset.Source.SemanticInputLimit = job.SemanticInputLimit
	dataset.Source.Coverage = silverCoverageForCompletedJob(*job)
	dataset.Source.Representations.Knowledge = knowledgeRepresentationForCoverage(dataset.Source.Coverage, job)
	dataset.PublishedAt = time.Now().UnixMilli()
	refreshSilverSourceIDs(&dataset)
	s.state.Published[job.BronzeSourceID] = dataset
	job.Coverage = dataset.Source.Coverage
	job.State = "completed"
	job.Checkpoints = nil
	job.Error = ""
	job.Attempts = 0
	job.RetryAt = 0
	job.Retryable = false
	job.UpdatedAt = time.Now().UnixMilli()
	s.pruneEntitiesLocked()
	s.state.Revision++
	s.state.DataRevision++
	return s.saveLocked()
}

func knowledgeRepresentationForCoverage(coverage silverCoverage, job *silverJob) silverRepresentation {
	state := silverRepresentationReady
	if coverage.SemanticState == silverSemanticPartial {
		state = silverRepresentationPartial
	} else if coverage.SemanticState == silverSemanticSkipped {
		state = silverRepresentationSkipped
	}
	return silverRepresentation{State: state, Producer: silverProducer{
		ProcessorID: silverKnowledgeID, ProcessorVersion: silverKnowledgeVersion,
		ModelID: job.ModelID, ModelRevision: job.ModelRevision,
	}}
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

func silverCoverageForCompletedJob(job silverJob) silverCoverage {
	coverage := job.Coverage
	if coverage.SemanticState != "" {
		return coverage
	}
	semanticFragments := 0
	completedFragments := 0
	reasons := map[string]bool{}
	for _, checkpoint := range job.Checkpoints {
		semanticFragments += checkpoint.SemanticFragments
		completedFragments += checkpoint.SemanticCompletedFragments
		if checkpoint.SemanticSkipReason != "" {
			reasons[checkpoint.SemanticSkipReason] = true
		}
	}
	switch {
	case completedFragments == semanticFragments:
		coverage.SemanticState = silverSemanticCompleted
	case completedFragments == 0:
		coverage.SemanticState = silverSemanticSkipped
	default:
		coverage.SemanticState = silverSemanticPartial
	}
	if len(reasons) > 0 {
		values := make([]string, 0, len(reasons))
		for reason := range reasons {
			values = append(values, reason)
		}
		sort.Strings(values)
		coverage.SemanticSkipReason = strings.Join(values, ",")
	}
	return coverage
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

func (s *silverService) cancelJobLocked(job *silverJob) error {
	job.State = "cancelled"
	job.Checkpoints = nil
	job.Error = ""
	job.RetryAt = 0
	job.Retryable = false
	job.UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	return s.saveLocked()
}

func (s *silverService) pruneEntitiesLocked() {
	used := map[string]bool{}
	for _, dataset := range s.state.Published {
		for _, entity := range dataset.Entities {
			used[entity.ID] = true
		}
	}
	for _, history := range s.state.History {
		for _, dataset := range history {
			for _, entity := range dataset.Entities {
				used[entity.ID] = true
			}
		}
	}
	for id := range s.state.Entities {
		if !used[id] {
			delete(s.state.Entities, id)
		}
	}
}

func (s *silverService) jobLocked(id string) *silverJob {
	for index := range s.state.Jobs {
		if s.state.Jobs[index].ID == id {
			return &s.state.Jobs[index]
		}
	}
	return nil
}

func checkpointFor(job *silverJob, index int, hash string) *silverCheckpoint {
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
	snapshot := silverSnapshot{SchemaVersion: silverSchemaVersion, Revision: s.state.DataRevision, Error: s.statusError}
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
		dataset.Source.Stale = !s.sourceMatchesCurrent(dataset.Source, current)
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
			BronzeSourceID: job.BronzeSourceID, Representation: job.Phase, State: job.State,
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
			BronzeSourceID: job.BronzeSourceID, Representation: job.Phase, State: job.State,
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
		Kind       string          `json:"kind"`
		Payload    json.RawMessage `json:"payload"`
		Evidence   []string        `json:"evidence"`
		Confidence *float64        `json:"confidence,omitempty"`
		Producer   silverProducer  `json:"producer"`
	}{observation.Kind, observation.Payload, observation.EvidenceIDs, observation.Confidence, observation.Producer})
}

func stableID(prefix string, value any) string {
	encoded, _ := json.Marshal(value)
	sum := sha256.Sum256(append(append([]byte(prefix), 0), encoded...))
	return hex.EncodeToString(sum[:])
}

func hashBytes(value []byte) string { sum := sha256.Sum256(value); return hex.EncodeToString(sum[:]) }

func randomUUID() (string, error) {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	value[6] = value[6]&0x0f | 0x40
	value[8] = value[8]&0x3f | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", value[0:4], value[4:6], value[6:8], value[8:10], value[10:16]), nil
}

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
