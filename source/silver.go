package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/csv"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"
)

const (
	silverSchemaVersion            = 1
	silverProcessorID              = "source.silver.pipeline"
	silverExtractionID             = "source.silver.format-extraction"
	silverExtractionVersion        = "2"
	silverResolverID               = "source.silver.candidate-resolver"
	silverResolverVersion          = "2"
	silverProcessorVersion         = "4-" + silverExtractionVersion + "-" + semanticProcessorVersion + "-" + silverResolverVersion
	silverResolutionMinimum        = 0.70
	silverMaximumBatchBytes        = 4096
	silverInspectionBytes          = 8192
	silverMaximumInputBytes        = 8 * 1024 * 1024
	silverMaximumContextFields     = 8
	silverMaximumContextCandidates = 32
	silverMaximumContextRunes      = 128
	silverMaximumStructureDepth    = 4
	silverReconcileInterval        = time.Second

	silverExtractionCompleted = "completed"
	silverExtractionSkipped   = "skipped"
	silverSemanticCompleted   = "completed"
	silverSemanticSkipped     = "skipped"
	silverSemanticPartial     = "partial"

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

type silverSource struct {
	BronzeSourceID      string         `json:"bronze_source_id"`
	BronzeContentSHA256 string         `json:"bronze_content_sha256"`
	Title               string         `json:"title"`
	Mime                string         `json:"mime"`
	ProcessorID         string         `json:"processor_id"`
	ProcessorVersion    string         `json:"processor_version"`
	ModelID             string         `json:"model_id,omitempty"`
	ModelRevision       string         `json:"model_revision,omitempty"`
	SemanticInputLimit  int            `json:"semantic_input_limit,omitempty"`
	Coverage            silverCoverage `json:"coverage"`
	EvidenceIDs         []string       `json:"evidence_ids"`
	ObservationIDs      []string       `json:"observation_ids"`
	EntityIDs           []string       `json:"entity_ids"`
	ClaimIDs            []string       `json:"claim_ids"`
	Stale               bool           `json:"stale,omitempty"`
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
	mu                 sync.Mutex
	path               string
	bronze             *bronzeStore
	configuration      silverConfiguration
	state              silverDiskState
	persisted          []byte
	wake               chan struct{}
	worker             sync.WaitGroup
	semantic           semanticModel
	afterCheckpoint    func(string, int)
	onPublish          func()
	statusError        string
	pendingPersistence bool
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
	if published, ok := s.state.Published[item.ID]; ok && s.sourceMatchesCurrent(published.Source, item) {
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
		job.ProcessorID == silverProcessorID && job.ProcessorVersion == silverProcessorVersion &&
		job.ModelID == modelID && job.ModelRevision == modelRevision && job.SemanticInputLimit == semanticInputLimit
}

func silverSourceMatchesItem(source silverSource, item bronzeItem, modelID, modelRevision string, semanticInputLimit int) bool {
	return silverSourceMatchesBronze(source, item) &&
		source.ProcessorID == silverProcessorID && source.ProcessorVersion == silverProcessorVersion &&
		source.ModelID == modelID && source.ModelRevision == modelRevision && source.SemanticInputLimit == semanticInputLimit
}

func (s *silverService) sourceMatchesCurrent(source silverSource, item bronzeItem) bool {
	if s.semantic == nil && source.ModelID != "" {
		return silverSourceMatchesBronze(source, item) &&
			source.ProcessorID == silverProcessorID && source.ProcessorVersion == silverProcessorVersion
	}
	modelID, modelRevision, semanticInputLimit := s.modelIdentity()
	return silverSourceMatchesItem(source, item, modelID, modelRevision, semanticInputLimit)
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
	if published, ok := s.state.Published[item.ID]; ok && s.sourceMatchesCurrent(published.Source, item) {
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
		Title: item.Title, Mime: item.Mime, ProcessorID: silverProcessorID, ProcessorVersion: silverProcessorVersion,
		ModelID: modelID, ModelRevision: modelRevision, SemanticInputLimit: semanticInputLimit,
		State: "queued", AcceptedAt: now, UpdatedAt: now,
	})
	s.state.Revision++
	if _, published := s.state.Published[item.ID]; published {
		s.state.DataRevision++
	}
	if err := s.saveLocked(); err != nil {
		return false, err
	}
	s.signal()
	return true, nil
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

	item, file, err := s.bronze.openContent(copy.BronzeSourceID)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return permanentSilverProcessError(fmt.Errorf("Bronze content is missing: %w", err))
		}
		return err
	}
	parsed, err := parseSilverReader(item, file)
	closeErr := file.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if item.Hash != copy.BronzeContentSHA256 {
		return s.cancelJob(jobID)
	}
	fragments := parsed.Fragments

	s.mu.Lock()
	job = s.jobLocked(jobID)
	if job == nil || job.State != "running" {
		s.mu.Unlock()
		return context.Canceled
	}
	job.Coverage = parsed.Coverage
	if job.Coverage.SemanticState == "" && s.semantic == nil {
		job.Coverage.SemanticState = silverSemanticSkipped
		job.Coverage.SemanticSkipReason = silverSkipModelUnavailable
	}
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
	return s.publish(jobID, item)
}

func (s *silverService) publish(jobID string, current bronzeItem) error {
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
		return errors.New("Silver job is missing checkpoints")
	}

	dataset := silverDataset{Source: silverSource{
		BronzeSourceID: job.BronzeSourceID, BronzeContentSHA256: job.BronzeContentSHA256,
		Title: job.Title, Mime: job.Mime, ProcessorID: job.ProcessorID, ProcessorVersion: job.ProcessorVersion,
		ModelID: job.ModelID, ModelRevision: job.ModelRevision, SemanticInputLimit: job.SemanticInputLimit,
	}, PublishedAt: time.Now().UnixMilli()}
	sort.Slice(job.Checkpoints, func(i, j int) bool { return job.Checkpoints[i].BatchIndex < job.Checkpoints[j].BatchIndex })
	entitySeen := map[string]bool{}
	for _, checkpoint := range job.Checkpoints {
		dataset.Evidence = append(dataset.Evidence, checkpoint.Evidence...)
		dataset.Observations = append(dataset.Observations, checkpoint.Observations...)
		s.resolveCheckpointCandidatesLocked(&dataset, checkpoint, entitySeen, job.ModelID, job.ModelRevision)
	}
	dataset.Source.Coverage = silverCoverageForCompletedJob(*job)
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
	if prior, ok := s.state.Published[job.BronzeSourceID]; ok {
		s.state.History[job.BronzeSourceID] = append(s.state.History[job.BronzeSourceID], prior)
	}
	s.state.Published[job.BronzeSourceID] = dataset
	job.Coverage = dataset.Source.Coverage
	job.State = "completed"
	job.Checkpoints = nil
	job.Error = ""
	job.Attempts = 0
	job.RetryAt = 0
	job.Retryable = false
	job.UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	s.state.DataRevision++
	err = s.saveLocked()
	if err == nil && s.onPublish != nil {
		s.onPublish()
	}
	return err
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

type resolvedSilverCandidate struct {
	Entity        silverEntity
	ObservationID string
}

func (s *silverService) resolveCheckpointCandidatesLocked(dataset *silverDataset, checkpoint silverCheckpoint, entitySeen map[string]bool, modelID, modelRevision string) {
	producer := silverProducer{ProcessorID: silverResolverID, ProcessorVersion: silverResolverVersion, ModelID: modelID, ModelRevision: modelRevision}
	resolved := map[string]resolvedSilverCandidate{}
	for _, candidate := range checkpoint.Entities {
		if candidate.Confidence < silverResolutionMinimum {
			continue
		}
		entity, ok := s.resolveEntityLocked(candidate.Label, candidate.Normalized, candidate.Type)
		if !ok {
			continue
		}
		resolved[candidate.Ref] = resolvedSilverCandidate{Entity: entity, ObservationID: candidate.ObservationID}
		if !entitySeen[entity.ID] {
			dataset.Entities = append(dataset.Entities, entity)
			entitySeen[entity.ID] = true
		}
		name, _ := json.Marshal(candidate.Label)
		appendSilverClaim(dataset, entity.ID, "name", name, "", []string{candidate.ObservationID}, producer)
		if candidate.Type != "" {
			entityType, _ := json.Marshal(candidate.Type)
			appendSilverClaim(dataset, entity.ID, "type", entityType, "", []string{candidate.ObservationID}, producer)
		}
	}
	for _, candidate := range checkpoint.Attributes {
		subject, ok := resolved[candidate.SubjectRef]
		if !ok || candidate.Confidence < silverResolutionMinimum {
			continue
		}
		appendSilverClaim(dataset, subject.Entity.ID, candidate.Predicate, candidate.Value, "",
			[]string{candidate.ObservationID, subject.ObservationID}, producer)
	}
	for _, candidate := range checkpoint.Relationships {
		subject, subjectOK := resolved[candidate.SubjectRef]
		object, objectOK := resolved[candidate.ObjectRef]
		if !subjectOK || !objectOK || candidate.Confidence < silverResolutionMinimum {
			continue
		}
		appendSilverClaim(dataset, subject.Entity.ID, candidate.Predicate, nil, object.Entity.ID,
			[]string{candidate.ObservationID, subject.ObservationID, object.ObservationID}, producer)
	}
}

func appendSilverClaim(dataset *silverDataset, subject, predicate string, value json.RawMessage, object string, observations []string, producer silverProducer) {
	claim := silverClaim{SubjectEntityID: subject, Predicate: predicate, Value: value, ObjectEntityID: object,
		SupportingObservationIDs: observations, Producer: producer, State: "active"}
	claim.ID = stableID("source-silver-claim", struct {
		Subject      string          `json:"subject"`
		Predicate    string          `json:"predicate"`
		Value        json.RawMessage `json:"value,omitempty"`
		Object       string          `json:"object,omitempty"`
		Observations []string        `json:"observations"`
		Producer     silverProducer  `json:"producer"`
	}{claim.SubjectEntityID, claim.Predicate, claim.Value, claim.ObjectEntityID, claim.SupportingObservationIDs, producer})
	dataset.Claims = append(dataset.Claims, claim)
}

func (s *silverService) resolveEntityLocked(label, normalized, entityType string) (silverEntity, bool) {
	entityType = strings.ToLower(strings.TrimSpace(entityType))
	var match *silverEntity
	for _, record := range s.state.Entities {
		if record.Normalized != normalized {
			continue
		}
		if record.TypeAmbiguous || record.Type != entityType {
			continue
		}
		if match != nil && match.ID != record.Entity.ID {
			return silverEntity{}, false
		}
		value := record.Entity
		match = &value
	}
	if match != nil {
		return *match, true
	}
	id, err := randomUUID()
	if err != nil {
		return silverEntity{}, false
	}
	entity := silverEntity{ID: id}
	s.state.Entities[id] = silverEntityRecord{Entity: entity, Label: label, Normalized: normalized, Type: entityType}
	return entity, true
}

func (s *silverService) backfillEntityTypesLocked() {
	types := map[string]map[string]bool{}
	collect := func(dataset silverDataset) {
		entityCandidates := map[string]bool{}
		for _, observation := range dataset.Observations {
			if observation.Kind == "entity-candidate" {
				entityCandidates[observation.ID] = true
			}
		}
		for _, claim := range dataset.Claims {
			if claim.State != "active" || claim.Predicate != "type" || claim.ObjectEntityID != "" ||
				claim.Producer.ProcessorID != silverResolverID || len(claim.SupportingObservationIDs) != 1 ||
				!entityCandidates[claim.SupportingObservationIDs[0]] {
				continue
			}
			var entityType string
			if err := json.Unmarshal(claim.Value, &entityType); err != nil {
				continue
			}
			entityType = strings.ToLower(strings.TrimSpace(entityType))
			if entityType == "" {
				continue
			}
			if types[claim.SubjectEntityID] == nil {
				types[claim.SubjectEntityID] = map[string]bool{}
			}
			types[claim.SubjectEntityID][entityType] = true
		}
	}
	for _, dataset := range s.state.Published {
		collect(dataset)
	}
	for _, history := range s.state.History {
		for _, dataset := range history {
			collect(dataset)
		}
	}
	for id, record := range s.state.Entities {
		switch len(types[id]) {
		case 0:
			continue
		case 1:
			for entityType := range types[id] {
				record.Type = entityType
			}
			record.TypeAmbiguous = false
		default:
			record.Type = ""
			record.TypeAmbiguous = true
		}
		s.state.Entities[id] = record
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
			BronzeSourceID: job.BronzeSourceID, State: job.State,
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
			BronzeSourceID: job.BronzeSourceID, State: job.State,
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

type parsedSilverFragment struct {
	Kind         string
	Selector     map[string]any
	Excerpt      string
	Payload      any
	Text         string
	Context      *parsedSilverContext
	SkipSemantic bool
}

type parsedSilverContext struct {
	ID       string
	Kind     string
	Selector map[string]any
	Payload  any
}

type silverParseResult struct {
	Fragments []parsedSilverFragment
	Coverage  silverCoverage
}

type silverSemanticBatch struct {
	Fragments []parsedSilverFragment
	Hash      string
}

func (f parsedSilverFragment) identity() string {
	value, _ := json.Marshal(struct {
		Kind         string               `json:"kind"`
		Selector     map[string]any       `json:"selector"`
		Payload      any                  `json:"payload"`
		Context      *parsedSilverContext `json:"context,omitempty"`
		SkipSemantic bool                 `json:"skip_semantic,omitempty"`
	}{f.Kind, f.Selector, f.Payload, f.Context, f.SkipSemantic})
	return string(value)
}

func buildSilverBatches(job silverJob, fragments []parsedSilverFragment, targetBytes int, model semanticModel) ([]silverSemanticBatch, error) {
	if len(fragments) == 0 {
		return nil, nil
	}
	if targetBytes <= 0 {
		return nil, errors.New("Silver semantic batch target must be positive")
	}
	if model != nil {
		maximumBytes := model.maximumInputBytes()
		if maximumBytes <= 0 {
			return nil, errors.New("Source model semantic input limit must be positive")
		}
		targetBytes = min(targetBytes, maximumBytes)
	}

	var batches []silverSemanticBatch
	var current []parsedSilverFragment
	flush := func() {
		if len(current) == 0 {
			return
		}
		fragmentsCopy := append([]parsedSilverFragment(nil), current...)
		identities := make([]string, 0, len(fragmentsCopy))
		for _, fragment := range fragmentsCopy {
			identities = append(identities, fragment.identity())
		}
		encoded, _ := json.Marshal(identities)
		batches = append(batches, silverSemanticBatch{Fragments: fragmentsCopy, Hash: hashBytes(encoded)})
		current = nil
	}

	for _, fragment := range fragments {
		candidate := append(append([]parsedSilverFragment(nil), current...), fragment)
		input, err := semanticInputForFragments(job, candidate)
		if err != nil {
			return nil, err
		}
		encoded, err := json.Marshal(input)
		if err != nil {
			return nil, err
		}
		semanticFragment := !fragment.SkipSemantic && strings.TrimSpace(fragment.Text) != ""
		if len(current) > 0 && semanticFragment && len(encoded) > targetBytes {
			flush()
			candidate = []parsedSilverFragment{fragment}
			input, err = semanticInputForFragments(job, candidate)
			if err != nil {
				return nil, err
			}
			encoded, err = json.Marshal(input)
			if err != nil {
				return nil, err
			}
		}
		current = candidate
	}
	flush()
	return batches, nil
}

func checkpointsMatchingBatches(checkpoints []silverCheckpoint, batches []silverSemanticBatch) []silverCheckpoint {
	matching := make([]silverCheckpoint, 0, min(len(checkpoints), len(batches)))
	seen := map[int]bool{}
	for _, checkpoint := range checkpoints {
		if checkpoint.BatchIndex < 0 || checkpoint.BatchIndex >= len(batches) ||
			checkpoint.BatchSHA256 != batches[checkpoint.BatchIndex].Hash || seen[checkpoint.BatchIndex] {
			continue
		}
		seen[checkpoint.BatchIndex] = true
		matching = append(matching, checkpoint)
	}
	return matching
}

func parseSilverReader(item bronzeItem, reader io.Reader) (silverParseResult, error) {
	if !declaredSilverText(item.Mime) && knownBinarySilverInput(item) {
		return silverParseResult{Coverage: silverCoverage{
			ExtractionState: silverExtractionSkipped, SemanticState: silverSemanticSkipped,
			SemanticSkipReason: silverSkipUnsupportedContent,
		}}, nil
	}
	buffered := bufio.NewReaderSize(reader, silverInspectionBytes)
	prefix, err := buffered.Peek(silverInspectionBytes)
	if err != nil && !errors.Is(err, io.EOF) && !errors.Is(err, bufio.ErrBufferFull) {
		return silverParseResult{}, err
	}
	if !declaredSilverText(item.Mime) && silverPrefixLooksBinary(prefix) {
		return silverParseResult{Coverage: silverCoverage{
			ExtractionState: silverExtractionSkipped, SemanticState: silverSemanticSkipped,
			SemanticSkipReason: silverSkipUnsupportedContent,
		}}, nil
	}
	data, err := io.ReadAll(io.LimitReader(buffered, silverMaximumInputBytes+1))
	if err != nil {
		return silverParseResult{}, err
	}
	if len(data) > silverMaximumInputBytes {
		return silverParseResult{Coverage: silverCoverage{
			ExtractionState: silverExtractionSkipped, SemanticState: silverSemanticSkipped,
			SemanticSkipReason: silverSkipSourceTooLarge,
		}}, nil
	}
	fragments, supported, err := parseSilverText(item, data)
	if err != nil {
		return silverParseResult{}, err
	}
	if !supported {
		return silverParseResult{Coverage: silverCoverage{
			ExtractionState: silverExtractionSkipped, SemanticState: silverSemanticSkipped,
			SemanticSkipReason: silverSkipUnsupportedContent,
		}}, nil
	}
	return silverParseResult{
		Fragments: fragments,
		Coverage:  silverCoverage{ExtractionState: silverExtractionCompleted},
	}, nil
}

func declaredSilverText(mime string) bool {
	mime = normalizedSilverMime(mime)
	return strings.HasPrefix(mime, "text/") || mime == "application/json" || mime == "application/csv"
}

func normalizedSilverMime(mime string) string {
	return strings.ToLower(strings.TrimSpace(strings.SplitN(mime, ";", 2)[0]))
}

func knownBinarySilverInput(item bronzeItem) bool {
	mime := normalizedSilverMime(item.Mime)
	if strings.HasPrefix(mime, "image/") || strings.HasPrefix(mime, "audio/") ||
		strings.HasPrefix(mime, "video/") || strings.HasPrefix(mime, "font/") {
		return true
	}
	switch mime {
	case "application/pdf", "application/zip", "application/gzip", "application/x-gzip",
		"application/x-7z-compressed", "application/x-rar-compressed":
		return true
	}
	lowerName := strings.ToLower(item.Title)
	for _, extension := range []string{".png", ".jpg", ".jpeg", ".gif", ".webp", ".heic", ".pdf", ".zip", ".gz", ".7z", ".rar", ".mp3", ".wav", ".flac", ".mp4", ".mov", ".avi", ".mkv"} {
		if strings.HasSuffix(lowerName, extension) {
			return true
		}
	}
	return false
}

func silverPrefixLooksBinary(data []byte) bool {
	if bytes.IndexByte(data, 0) >= 0 {
		return true
	}
	valid := data
	if !utf8.Valid(valid) {
		valid = nil
		for trim := 1; trim < utf8.UTFMax && trim < len(data); trim++ {
			candidate := data[:len(data)-trim]
			if utf8.Valid(candidate) {
				valid = candidate
				break
			}
		}
		if valid == nil {
			return true
		}
	}
	control := 0
	for _, r := range string(valid) {
		if r < 0x20 && r != '\n' && r != '\r' && r != '\t' {
			control++
		}
	}
	return len(valid) > 0 && control*100 > len(valid)
}

func parseSilverText(item bronzeItem, data []byte) ([]parsedSilverFragment, bool, error) {
	if len(data) == 0 {
		return []parsedSilverFragment{}, true, nil
	}
	byteOffset := 0
	if bytes.HasPrefix(data, []byte{0xef, 0xbb, 0xbf}) {
		data = data[3:]
		byteOffset = 3
	}
	declaredText := declaredSilverText(item.Mime)
	if !utf8.Valid(data) || bytes.IndexByte(data, 0) >= 0 {
		if declaredText {
			return nil, true, permanentSilverProcessError(errors.New("text-like Bronze is not valid UTF-8"))
		}
		return nil, false, nil
	}
	control := 0
	for _, r := range string(data) {
		if r < 0x20 && r != '\n' && r != '\r' && r != '\t' {
			control++
		}
	}
	if !declaredText && control*100 > len(data) {
		return nil, false, nil
	}
	text := string(data)
	lowerName := strings.ToLower(item.Title)
	mime := normalizedSilverMime(item.Mime)
	if mime == "application/json" || strings.HasSuffix(lowerName, ".json") {
		if fragments, ok := parseJSONFragments(text); ok {
			return fragments, true, nil
		}
	}
	if mime == "text/csv" || mime == "application/csv" || strings.HasSuffix(lowerName, ".csv") {
		if fragments, ok := parseCSVFragments(text); ok {
			return fragments, true, nil
		}
	}
	if mime == "text/markdown" || strings.HasSuffix(lowerName, ".md") || strings.HasSuffix(lowerName, ".markdown") {
		if fragments := parseMarkdownFragments(text); len(fragments) > 0 {
			return offsetSilverFragments(fragments, byteOffset), true, nil
		}
	}
	return offsetSilverFragments(parseGenericFragments(text), byteOffset), true, nil
}

func offsetSilverFragments(fragments []parsedSilverFragment, byteOffset int) []parsedSilverFragment {
	if byteOffset == 0 {
		return fragments
	}
	for index := range fragments {
		if start, ok := fragments[index].Selector["start_byte"].(int); ok {
			fragments[index].Selector["start_byte"] = start + byteOffset
		}
		if end, ok := fragments[index].Selector["end_byte"].(int); ok {
			fragments[index].Selector["end_byte"] = end + byteOffset
		}
	}
	return fragments
}

func parseJSONFragments(text string) ([]parsedSilverFragment, bool) {
	decoder := json.NewDecoder(strings.NewReader(text))
	decoder.UseNumber()
	var value any
	if decoder.Decode(&value) != nil {
		return nil, false
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return nil, false
	}
	var fragments []parsedSilverFragment
	var visit func(any, string, *parsedSilverContext)
	visit = func(node any, path string, context *parsedSilverContext) {
		switch typed := node.(type) {
		case map[string]any:
			keys := make([]string, 0, len(typed))
			for key := range typed {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			for _, key := range keys {
				childPath := path + "/" + jsonPointerEscape(key)
				visit(typed[key], childPath, jsonObjectContext(typed, keys, path, key))
			}
		case []any:
			for index, child := range typed {
				visit(child, path+"/"+strconv.Itoa(index), context)
			}
		default:
			encoded, _ := json.Marshal(typed)
			fragment := parsedSilverFragment{
				Kind: "parsed-json-value", Selector: map[string]any{"kind": "json-pointer", "pointer": path},
				Excerpt: path + ": " + string(encoded), Payload: map[string]any{"path": path, "value": typed},
				Text: scalarText(typed), Context: context,
			}
			if value, ok := typed.(string); ok {
				if children, structured := structuredChildren(value, map[string]any{"kind": "json-string-child", "pointer": path}, context); structured {
					fragment.SkipSemantic = true
					fragments = append(fragments, fragment)
					fragments = append(fragments, children...)
					return
				}
			}
			fragments = append(fragments, fragment)
		}
	}
	visit(value, "", nil)
	return fragments, true
}

func parseCSVFragments(text string) ([]parsedSilverFragment, bool) {
	records, ok := parseCSVRecords(text)
	if !ok || len(records) == 0 {
		return nil, false
	}
	headers := records[0].Values
	fragments := make([]parsedSilverFragment, 0, len(records)*2)
	fragments = append(fragments, parsedSilverFragment{
		Kind: "parsed-table-header", Selector: map[string]any{"kind": "table-row", "row": 1, "source_line": records[0].SourceLine},
		Excerpt: strings.Join(headers, ", "), Payload: map[string]any{"row": 1, "values": headers},
	})
	for index, csvRecord := range records[1:] {
		record := csvRecord.Values
		row := index + 2
		payload := map[string]any{"row": row, "values": record}
		if len(headers) == len(record) {
			columns := map[string]string{}
			unique := true
			for column, header := range headers {
				if header == "" {
					unique = false
					break
				}
				if _, exists := columns[header]; exists {
					unique = false
					break
				}
				columns[header] = record[column]
			}
			if unique {
				payload["columns"] = columns
			}
		}
		fragments = append(fragments, parsedSilverFragment{
			Kind: "parsed-table-row", Selector: map[string]any{"kind": "table-row", "row": row, "source_line": csvRecord.SourceLine},
			Excerpt: strings.Join(record, ", "), Payload: payload, Text: strings.Join(record, " "), SkipSemantic: true,
		})
		for columnIndex, value := range record {
			if strings.TrimSpace(value) == "" {
				continue
			}
			header := ""
			if columnIndex < len(headers) {
				header = headers[columnIndex]
			}
			selector := map[string]any{
				"kind": "table-cell", "row": row, "column_index": columnIndex + 1,
			}
			if header != "" {
				selector["column"] = header
			}
			if columnIndex < len(csvRecord.Positions) {
				selector["source_line"] = csvRecord.Positions[columnIndex].Line
				selector["source_column"] = csvRecord.Positions[columnIndex].Column
			}
			context := csvRecordContext(row, headers, record, columnIndex)
			if children, structured := structuredChildren(value, selector, context); structured {
				fragments = append(fragments, children...)
				continue
			}
			fragments = append(fragments, parsedSilverFragment{
				Kind: "parsed-table-cell", Selector: selector,
				Excerpt: tableCellExcerpt(header, columnIndex, value),
				Payload: map[string]any{"row": row, "column": header, "column_index": columnIndex + 1, "value": value},
				Text:    value, Context: context,
			})
		}
	}
	return fragments, true
}

type csvSilverFieldPosition struct {
	Line   int
	Column int
}

type csvSilverRecord struct {
	Values     []string
	Positions  []csvSilverFieldPosition
	SourceLine int
}

// parseCSVRecords delegates RFC 4180 details, including escaped quotes and
// quoted newlines, to Go's standard library. Logical row numbers remain stable
// while FieldPos adds physical source locations for more precise selectors.
func parseCSVRecords(text string) ([]csvSilverRecord, bool) {
	reader := csv.NewReader(strings.NewReader(text))
	reader.FieldsPerRecord = 0
	var records []csvSilverRecord
	for {
		values, err := reader.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, false
		}
		record := csvSilverRecord{Values: append([]string(nil), values...)}
		for index := range values {
			line, column := reader.FieldPos(index)
			if index == 0 {
				record.SourceLine = line
			}
			record.Positions = append(record.Positions, csvSilverFieldPosition{Line: line, Column: column})
		}
		records = append(records, record)
	}
	return records, true
}

type structuredScalarUnit struct {
	Kind     string
	Selector map[string]any
	Payload  any
	Text     string
	Excerpt  string
	Start    int
	End      int
}

func jsonPointerEscape(value string) string {
	return strings.ReplaceAll(strings.ReplaceAll(value, "~", "~0"), "/", "~1")
}

func newParsedSilverContext(kind string, selector map[string]any, payload any) *parsedSilverContext {
	context := &parsedSilverContext{Kind: kind, Selector: selector, Payload: payload}
	context.ID = stableID("source-silver-parent-context", struct {
		Kind     string         `json:"kind"`
		Selector map[string]any `json:"selector"`
		Payload  any            `json:"payload"`
	}{kind, selector, payload})
	return context
}

func boundedContextString(value string) string {
	return truncate(value, silverMaximumContextRunes)
}

func contextStringFits(value string) bool {
	return len(value) <= silverMaximumContextRunes*utf8.UTFMax &&
		utf8.RuneCountInString(value) <= silverMaximumContextRunes
}

func jsonObjectContext(object map[string]any, keys []string, path, excludedKey string) *parsedSilverContext {
	fields := make([]map[string]any, 0, min(len(keys), silverMaximumContextFields))
	inspected := 0
	for _, key := range keys {
		if key == excludedKey {
			continue
		}
		inspected++
		if inspected > silverMaximumContextCandidates {
			break
		}
		if len(fields) >= silverMaximumContextFields {
			break
		}
		value := object[key]
		switch typed := value.(type) {
		case string:
			if !contextStringFits(typed) {
				continue
			}
			if _, structured := decomposeStructuredString(typed, 0); structured {
				continue
			}
			value = boundedContextString(typed)
		case json.Number, bool:
		default:
			continue
		}
		fields = append(fields, map[string]any{
			"name": key, "path": path + "/" + jsonPointerEscape(key), "value": value,
		})
	}
	if len(fields) == 0 {
		return nil
	}
	selector := map[string]any{"kind": "json-object-context", "pointer": path, "excluded_key": excludedKey}
	return newParsedSilverContext("json-object", selector, map[string]any{"path": path, "fields": fields})
}

func csvRecordContext(row int, headers, values []string, excludedColumn int) *parsedSilverContext {
	fields := make([]map[string]any, 0, min(len(values), silverMaximumContextFields))
	inspected := 0
	for columnIndex, value := range values {
		if columnIndex == excludedColumn || strings.TrimSpace(value) == "" {
			continue
		}
		inspected++
		if inspected > silverMaximumContextCandidates {
			break
		}
		if !contextStringFits(value) {
			continue
		}
		if _, structured := decomposeStructuredString(value, 0); structured {
			continue
		}
		if len(fields) >= silverMaximumContextFields {
			break
		}
		header := ""
		if columnIndex < len(headers) {
			header = headers[columnIndex]
		}
		fields = append(fields, map[string]any{
			"column": header, "column_index": columnIndex + 1, "value": boundedContextString(value),
		})
	}
	if len(fields) == 0 {
		return nil
	}
	selector := map[string]any{"kind": "table-record-context", "row": row, "excluded_column_index": excludedColumn + 1}
	return newParsedSilverContext("table-record", selector, map[string]any{"row": row, "fields": fields})
}

func tableCellExcerpt(header string, columnIndex int, value string) string {
	label := header
	if label == "" {
		label = fmt.Sprintf("column %d", columnIndex+1)
	}
	return truncate(label+": "+value, 240)
}

func structuredChildren(value string, baseSelector map[string]any, context *parsedSilverContext) ([]parsedSilverFragment, bool) {
	units, ok := decomposeStructuredString(value, 0)
	if !ok {
		return nil, false
	}
	fragments := make([]parsedSilverFragment, 0, len(units))
	for index, unit := range units {
		selector := make(map[string]any, len(baseSelector)+2)
		for key, item := range baseSelector {
			selector[key] = item
		}
		if selector["kind"] == "table-cell" {
			selector["kind"] = "table-cell-child"
		}
		selector["child"] = index + 1
		selector["structure"] = unit.Selector
		fragments = append(fragments, parsedSilverFragment{
			Kind: unit.Kind, Selector: selector, Excerpt: truncate(unit.Excerpt, 240),
			Payload: unit.Payload, Text: unit.Text, Context: context,
		})
	}
	return fragments, true
}

func decomposeStructuredString(value string, depth int) ([]structuredScalarUnit, bool) {
	if depth >= silverMaximumStructureDepth {
		return nil, false
	}
	trimmed := strings.TrimSpace(value)
	if len(trimmed) > 1 && (trimmed[0] == '{' || trimmed[0] == '[') {
		decoder := json.NewDecoder(strings.NewReader(trimmed))
		decoder.UseNumber()
		var decoded any
		if decoder.Decode(&decoded) == nil && decoder.Decode(&struct{}{}) == io.EOF {
			units := embeddedJSONUnits(decoded, "", depth+1)
			if len(units) > 0 {
				return units, true
			}
		}
	}
	return keyValueLineUnits(value, depth)
}

func embeddedJSONUnits(value any, path string, depth int) []structuredScalarUnit {
	var units []structuredScalarUnit
	switch typed := value.(type) {
	case map[string]any:
		keys := make([]string, 0, len(typed))
		for key := range typed {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			units = append(units, embeddedJSONUnits(typed[key], path+"/"+jsonPointerEscape(key), depth)...)
		}
	case []any:
		for index, child := range typed {
			units = append(units, embeddedJSONUnits(child, path+"/"+strconv.Itoa(index), depth)...)
		}
	default:
		if text, ok := typed.(string); ok {
			if children, structured := decomposeStructuredString(text, depth); structured {
				for _, child := range children {
					child.Selector = map[string]any{"kind": "json-value-child", "pointer": path, "structure": child.Selector}
					child.Payload = map[string]any{"path": path, "value": child.Payload}
					units = append(units, child)
				}
				return units
			}
		}
		encoded, _ := json.Marshal(typed)
		units = append(units, structuredScalarUnit{
			Kind: "parsed-embedded-json-value", Selector: map[string]any{"kind": "json-pointer", "pointer": path},
			Payload: map[string]any{"path": path, "value": typed}, Text: scalarText(typed),
			Excerpt: path + ": " + string(encoded),
		})
	}
	return units
}

func keyValueLineUnits(value string, depth int) ([]structuredScalarUnit, bool) {
	type parsedLine struct {
		key, value, text string
		start, end       int
	}
	var lines []parsedLine
	offset := 0
	for _, withNewline := range strings.SplitAfter(value, "\n") {
		line := strings.TrimSuffix(withNewline, "\n")
		line = strings.TrimSuffix(line, "\r")
		trimmed := strings.TrimSpace(line)
		if trimmed != "" {
			colon := strings.IndexByte(trimmed, ':')
			if colon <= 0 {
				return nil, false
			}
			key := strings.TrimSpace(trimmed[:colon])
			item := strings.TrimSpace(trimmed[colon+1:])
			if !validStructuredKey(key) || item == "" {
				return nil, false
			}
			start := offset + strings.Index(line, trimmed)
			lines = append(lines, parsedLine{key: key, value: item, text: trimmed, start: start, end: start + len(trimmed)})
		}
		offset += len(withNewline)
	}
	if len(lines) < 2 {
		return nil, false
	}
	units := make([]structuredScalarUnit, 0, len(lines))
	for _, line := range lines {
		if children, structured := decomposeStructuredString(line.value, depth+1); structured {
			for _, child := range children {
				child.Selector = map[string]any{"kind": "key-value-child", "key": line.key, "structure": child.Selector}
				child.Payload = map[string]any{"key": line.key, "value": child.Payload}
				child.Text = line.key + ": " + child.Text
				child.Excerpt = line.key + ": " + child.Excerpt
				child.Start, child.End = line.start, line.end
				units = append(units, child)
			}
			continue
		}
		units = append(units, structuredScalarUnit{
			Kind: "parsed-key-value", Selector: map[string]any{"kind": "key-value", "key": line.key},
			Payload: map[string]any{"key": line.key, "value": line.value}, Text: line.text,
			Excerpt: line.text, Start: line.start, End: line.end,
		})
	}
	return units, true
}

func validStructuredKey(value string) bool {
	if value == "" || len([]rune(value)) > 120 {
		return false
	}
	hasLetterOrDigit := false
	for _, character := range value {
		if unicode.IsLetter(character) || unicode.IsDigit(character) {
			hasLetterOrDigit = true
			continue
		}
		switch character {
		case ' ', '\t', '-', '_', '.', '/', '(', ')', '#':
		default:
			return false
		}
	}
	return hasLetterOrDigit
}

func parseMarkdownFragments(text string) []parsedSilverFragment {
	var fragments []parsedSilverFragment
	offset := 0
	paragraphStart := -1
	var paragraph []string
	flush := func(end int) {
		if len(paragraph) == 0 {
			return
		}
		start, trimmedEnd, value := trimmedSilverRange(text, paragraphStart, end)
		fragment := fragmentWithTextRange("text-block", start, trimmedEnd, value, map[string]any{"text": value})
		fragments = append(fragments, expandStructuredTextFragment(fragment)...)
		paragraph = nil
		paragraphStart = -1
	}
	for _, lineWithNewline := range strings.SplitAfter(text, "\n") {
		line := strings.TrimSuffix(strings.TrimSuffix(lineWithNewline, "\n"), "\r")
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "#") {
			prefix := len(trimmed) - len(strings.TrimLeft(trimmed, "#"))
			if prefix <= 6 && len(trimmed) > prefix && trimmed[prefix] == ' ' {
				flush(offset)
				value := strings.TrimSpace(trimmed[prefix:])
				lineStart := offset + strings.Index(line, value)
				fragments = append(fragments, fragmentWithTextRange("markdown-heading", lineStart, lineStart+len(value), value, map[string]any{"level": prefix, "text": value}))
				offset += len(lineWithNewline)
				continue
			}
		}
		if trimmed == "" {
			flush(offset)
			offset += len(lineWithNewline)
			continue
		}
		if paragraphStart < 0 {
			paragraphStart = offset
		}
		paragraph = append(paragraph, line)
		offset += len(lineWithNewline)
	}
	flush(len(text))
	return splitLargeFragments(fragments)
}

func parseGenericFragments(text string) []parsedSilverFragment {
	var fragments []parsedSilverFragment
	start := 0
	for start < len(text) {
		end := strings.Index(text[start:], "\n\n")
		if end < 0 {
			end = len(text)
		} else {
			end = start + end
		}
		trimmedStart, trimmedEnd, value := trimmedSilverRange(text, start, end)
		if value != "" {
			fragment := fragmentWithTextRange("text-block", trimmedStart, trimmedEnd, value, map[string]any{"text": value})
			fragments = append(fragments, expandStructuredTextFragment(fragment)...)
		}
		if end == len(text) {
			break
		}
		start = end + 2
	}
	return splitLargeFragments(fragments)
}

func expandStructuredTextFragment(fragment parsedSilverFragment) []parsedSilverFragment {
	units, ok := decomposeStructuredString(fragment.Text, 0)
	if !ok {
		return []parsedSilverFragment{fragment}
	}
	fragment.SkipSemantic = true
	fragments := []parsedSilverFragment{fragment}
	start, _ := fragment.Selector["start_byte"].(int)
	end, _ := fragment.Selector["end_byte"].(int)
	for index, unit := range units {
		selector := map[string]any{"kind": "text-block-child", "start_byte": start, "end_byte": end, "child": index + 1, "structure": unit.Selector}
		if unit.End > unit.Start {
			selector["kind"] = "utf8-byte-range"
			selector["start_byte"] = start + unit.Start
			selector["end_byte"] = start + unit.End
		}
		fragments = append(fragments, parsedSilverFragment{
			Kind: unit.Kind, Selector: selector, Excerpt: truncate(unit.Excerpt, 240),
			Payload: unit.Payload, Text: unit.Text,
		})
	}
	return fragments
}

func trimmedSilverRange(text string, start, end int) (int, int, string) {
	value := text[start:end]
	trimmedLeft := strings.TrimLeftFunc(value, unicode.IsSpace)
	trimmed := strings.TrimRightFunc(trimmedLeft, unicode.IsSpace)
	trimmedStart := start + len(value) - len(trimmedLeft)
	return trimmedStart, trimmedStart + len(trimmed), trimmed
}

func splitLargeFragments(input []parsedSilverFragment) []parsedSilverFragment {
	var output []parsedSilverFragment
	for _, fragment := range input {
		if len(fragment.Text) <= silverMaximumBatchBytes ||
			(fragment.Kind != "text-block" && fragment.Kind != "markdown-heading") {
			output = append(output, fragment)
			continue
		}
		base, _ := fragment.Selector["start_byte"].(int)
		remaining := fragment.Text
		consumed := 0
		for len(remaining) > 0 {
			end := min(len(remaining), silverMaximumBatchBytes)
			for end < len(remaining) && end > 0 && !utf8.RuneStart(remaining[end]) {
				end--
			}
			if end == 0 {
				_, size := utf8.DecodeRuneInString(remaining)
				end = size
			}
			part := remaining[:end]
			partFragment := fragmentWithTextRange(fragment.Kind, base+consumed, base+consumed+len(part), part, map[string]any{"text": part})
			partFragment.Context = fragment.Context
			partFragment.SkipSemantic = fragment.SkipSemantic
			output = append(output, partFragment)
			remaining = remaining[end:]
			consumed += end
		}
	}
	return output
}

func fragmentWithTextRange(kind string, start, end int, text string, payload any) parsedSilverFragment {
	return parsedSilverFragment{Kind: kind, Selector: map[string]any{"kind": "utf8-byte-range", "start_byte": start, "end_byte": end}, Excerpt: truncate(text, 240), Payload: payload, Text: text}
}

func silverEvidenceForFragment(job silverJob, fragment parsedSilverFragment) silverEvidence {
	evidence := silverEvidence{
		BronzeSourceID: job.BronzeSourceID, BronzeContentSHA256: job.BronzeContentSHA256,
		Selector: fragment.Selector, Excerpt: fragment.Excerpt,
	}
	evidence.ID = stableID("source-silver-evidence", struct {
		Source, Hash string
		Selector     map[string]any
	}{job.BronzeSourceID, job.BronzeContentSHA256, fragment.Selector})
	return evidence
}

func semanticInputForFragments(job silverJob, fragments []parsedSilverFragment) (semanticInput, error) {
	input := semanticInput{Title: job.Title, Mime: job.Mime, Fragments: []semanticFragmentInput{}}
	contexts := map[string]bool{}
	for _, fragment := range fragments {
		if fragment.SkipSemantic || strings.TrimSpace(fragment.Text) == "" {
			continue
		}
		payload, err := json.Marshal(fragment.Payload)
		if err != nil {
			return semanticInput{}, err
		}
		payload, err = compactSemanticPayload(fragment, payload)
		if err != nil {
			return semanticInput{}, err
		}
		contextID := ""
		if fragment.Context != nil {
			contextID = fragment.Context.ID
			if !contexts[contextID] {
				contextPayload, err := json.Marshal(fragment.Context.Payload)
				if err != nil {
					return semanticInput{}, err
				}
				input.Contexts = append(input.Contexts, semanticContextInput{
					ID: fragment.Context.ID, Kind: fragment.Context.Kind,
					Selector: fragment.Context.Selector, Payload: contextPayload,
				})
				contexts[contextID] = true
			}
		}
		input.Fragments = append(input.Fragments, semanticFragmentInput{
			ID: silverEvidenceForFragment(job, fragment).ID, Kind: fragment.Kind,
			Selector: fragment.Selector, ParentContextID: contextID, Payload: payload,
		})
	}
	return input, nil
}

func extractSilverBatch(ctx context.Context, job silverJob, fragments []parsedSilverFragment, index int, batchHash string, model semanticModel) (silverCheckpoint, error) {
	producer := silverProducer{ProcessorID: silverExtractionID, ProcessorVersion: silverExtractionVersion}
	checkpoint := silverCheckpoint{BatchIndex: index, BatchSHA256: batchHash}
	evidenceByID := make(map[string]silverEvidence, len(fragments))
	for _, fragment := range fragments {
		evidence := silverEvidenceForFragment(job, fragment)
		payload, err := json.Marshal(fragment.Payload)
		if err != nil {
			return silverCheckpoint{}, err
		}
		observation := silverObservation{Kind: fragment.Kind, Payload: payload, EvidenceIDs: []string{evidence.ID}, Producer: producer}
		observation.ID = observationID(observation)
		checkpoint.Evidence = append(checkpoint.Evidence, evidence)
		checkpoint.Observations = append(checkpoint.Observations, observation)
		evidenceByID[evidence.ID] = evidence
	}
	input, err := semanticInputForFragments(job, fragments)
	if err != nil {
		return silverCheckpoint{}, err
	}
	checkpoint.SemanticFragments = len(input.Fragments)
	if model == nil {
		return checkpoint, nil
	}
	modelID, modelRevision := model.identity()
	if modelID != job.ModelID || modelRevision != job.ModelRevision ||
		model.maximumInputBytes() != job.SemanticInputLimit {
		return silverCheckpoint{}, errors.New("Source model identity or input limit changed during Silver processing")
	}
	if len(input.Fragments) == 0 {
		return checkpoint, nil
	}
	encodedInput, err := json.Marshal(input)
	if err != nil {
		return silverCheckpoint{}, err
	}
	// A single deterministic fragment can be larger than the model context.
	// The batcher isolates it, and Silver still publishes its exact evidence
	// and deterministic observation instead of retrying a permanent failure.
	if len(encodedInput) > model.maximumInputBytes() {
		checkpoint.SemanticSkipReason = silverSkipFragmentTooLarge
		return checkpoint, nil
	}
	semanticFragments := make([]parsedSilverFragment, 0, len(fragments))
	for _, fragment := range fragments {
		if !fragment.SkipSemantic && strings.TrimSpace(fragment.Text) != "" {
			semanticFragments = append(semanticFragments, fragment)
		}
	}
	recovery, err := recoverSilverSemanticFragments(ctx, job, semanticFragments, model, fmt.Sprintf("%d", index))
	if err != nil {
		return silverCheckpoint{}, err
	}
	checkpoint.SemanticCompletedFragments = recovery.CompletedFragments
	if recovery.SkippedContractFragment {
		checkpoint.SemanticSkipReason = silverSkipModelContract
	}
	semanticProducer := silverProducer{ProcessorID: semanticProcessorID, ProcessorVersion: semanticProcessorVersion, ModelID: modelID, ModelRevision: modelRevision}
	for _, fragmentResult := range recovery.Fragments {
		evidence, ok := evidenceByID[fragmentResult.FragmentID]
		if !ok {
			return silverCheckpoint{}, errors.New("semantic result refers to missing Silver evidence")
		}
		semanticRef := func(ref string) string { return fragmentResult.FragmentID + ":" + ref }
		for _, candidate := range fragmentResult.Entities {
			label := strings.TrimSpace(candidate.Label)
			entityType := strings.TrimSpace(candidate.Type)
			payload, _ := json.Marshal(map[string]any{"ref": candidate.Ref, "label": label, "type": entityType})
			semantic := silverObservation{Kind: "entity-candidate", Payload: payload, EvidenceIDs: []string{evidence.ID}, Confidence: candidate.Confidence, Producer: semanticProducer}
			semantic.ID = observationID(semantic)
			checkpoint.Observations = append(checkpoint.Observations, semantic)
			checkpoint.Entities = append(checkpoint.Entities, silverEntityCandidate{
				ObservationID: semantic.ID, Ref: semanticRef(candidate.Ref), Label: label, Type: entityType,
				Normalized: strings.ToLower(strings.Join(strings.Fields(label), " ")), Confidence: *candidate.Confidence,
			})
		}
		for _, candidate := range fragmentResult.Attributes {
			payload, _ := json.Marshal(struct {
				SubjectRef string          `json:"subject_ref"`
				Predicate  string          `json:"predicate"`
				Value      json.RawMessage `json:"value"`
			}{candidate.SubjectRef, candidate.Predicate, candidate.Value})
			semantic := silverObservation{Kind: "attribute-candidate", Payload: payload, EvidenceIDs: []string{evidence.ID}, Confidence: candidate.Confidence, Producer: semanticProducer}
			semantic.ID = observationID(semantic)
			checkpoint.Observations = append(checkpoint.Observations, semantic)
			checkpoint.Attributes = append(checkpoint.Attributes, silverAttributeCandidate{
				ObservationID: semantic.ID, SubjectRef: semanticRef(candidate.SubjectRef), Predicate: candidate.Predicate,
				Value: candidate.Value, Confidence: *candidate.Confidence,
			})
		}
		for _, candidate := range fragmentResult.Relationships {
			payload, _ := json.Marshal(map[string]any{"subject_ref": candidate.SubjectRef, "predicate": candidate.Predicate, "object_ref": candidate.ObjectRef})
			semantic := silverObservation{Kind: "relationship-candidate", Payload: payload, EvidenceIDs: []string{evidence.ID}, Confidence: candidate.Confidence, Producer: semanticProducer}
			semantic.ID = observationID(semantic)
			checkpoint.Observations = append(checkpoint.Observations, semantic)
			checkpoint.Relationships = append(checkpoint.Relationships, silverRelationshipCandidate{
				ObservationID: semantic.ID, SubjectRef: semanticRef(candidate.SubjectRef), Predicate: candidate.Predicate,
				ObjectRef: semanticRef(candidate.ObjectRef), Confidence: *candidate.Confidence,
			})
		}
	}
	return checkpoint, nil
}

type silverSemanticRecovery struct {
	Fragments               []semanticFragmentResult
	CompletedFragments      int
	SkippedContractFragment bool
}

func (r *silverSemanticRecovery) append(next silverSemanticRecovery) error {
	r.Fragments = append(r.Fragments, next.Fragments...)
	r.CompletedFragments += next.CompletedFragments
	r.SkippedContractFragment = r.SkippedContractFragment || next.SkippedContractFragment
	if len(r.Fragments) > semanticMaximumFragments || semanticCandidateCount(r.Fragments) > semanticMaximumCandidates {
		return errors.New("semantic recovery exceeded aggregate result bounds")
	}
	return nil
}

type silverSemanticRecoveryBudget struct {
	RemainingFragments  int
	RemainingCandidates int
}

func (b *silverSemanticRecoveryBudget) reserve(result semanticResult) error {
	fragments := len(result.Fragments)
	candidates := semanticCandidateCount(result.Fragments)
	if fragments > b.RemainingFragments {
		return fmt.Errorf("semantic recovery needs %d fragment slots; %d remain", fragments, b.RemainingFragments)
	}
	if candidates > b.RemainingCandidates {
		return fmt.Errorf("semantic recovery needs %d candidate slots; %d remain", candidates, b.RemainingCandidates)
	}
	b.RemainingFragments -= fragments
	b.RemainingCandidates -= candidates
	return nil
}

func semanticCandidateCount(fragments []semanticFragmentResult) int {
	count := 0
	for _, fragment := range fragments {
		count += len(fragment.Entities) + len(fragment.Attributes) + len(fragment.Relationships)
	}
	return count
}

func recoverSilverSemanticFragments(
	ctx context.Context,
	job silverJob,
	fragments []parsedSilverFragment,
	model semanticModel,
	batchPath string,
) (silverSemanticRecovery, error) {
	budget := silverSemanticRecoveryBudget{
		RemainingFragments:  semanticMaximumFragments,
		RemainingCandidates: semanticMaximumCandidates,
	}
	return recoverSilverSemanticFragmentsWithinBudget(ctx, job, fragments, model, batchPath, &budget)
}

func recoverSilverSemanticFragmentsWithinBudget(
	ctx context.Context,
	job silverJob,
	fragments []parsedSilverFragment,
	model semanticModel,
	batchPath string,
	budget *silverSemanticRecoveryBudget,
) (silverSemanticRecovery, error) {
	input, err := semanticInputForFragments(job, fragments)
	if err != nil {
		return silverSemanticRecovery{}, err
	}
	if len(input.Fragments) == 0 {
		return silverSemanticRecovery{}, nil
	}

	for attempt := 1; attempt <= semanticContractMaximumAttempts; attempt++ {
		if err := ctx.Err(); err != nil {
			return silverSemanticRecovery{}, err
		}
		result, err := model.extract(ctx, input)
		if err != nil {
			var contract *semanticContractError
			if !errors.As(err, &contract) {
				return silverSemanticRecovery{}, err
			}
			logSilverSemanticContractFailure(job, batchPath, attempt, contract)
			continue
		}

		// With one input fragment, the model cannot authoritatively choose a
		// different identity. Preserve its proposed content and bind the sole
		// returned object to Source's expected evidence ID.
		if len(input.Fragments) == 1 && len(result.Fragments) == 1 {
			result.Fragments[0].FragmentID = input.Fragments[0].ID
		}
		var contractCause error
		if err := validateSemanticResult(result); err != nil {
			contractCause = err
		} else if err := validateSemanticResultMapping(input, result); err != nil {
			contractCause = err
		} else if err := budget.reserve(result); err != nil {
			contractCause = err
		}
		if contractCause == nil {
			return silverSemanticRecovery{Fragments: result.Fragments, CompletedFragments: len(input.Fragments)}, nil
		}
		responseHash := result.responseHash
		if responseHash == "" {
			encoded, _ := json.Marshal(result)
			responseHash = hashBytes(encoded)
		}
		contract := newSemanticContractError(input, result, responseHash, contractCause)
		logSilverSemanticContractFailure(job, batchPath, attempt, contract)
	}

	if len(fragments) == 1 {
		log.Printf(
			"Silver semantic contract recovery: job=%s batch=%s action=skip_fragment fragment_id=%q attempts=%d",
			job.ID, batchPath, input.Fragments[0].ID, semanticContractMaximumAttempts,
		)
		return silverSemanticRecovery{SkippedContractFragment: true}, nil
	}

	middle := len(fragments) / 2
	log.Printf(
		"Silver semantic contract recovery: job=%s batch=%s action=split fragments=%d left=%d right=%d attempts=%d",
		job.ID, batchPath, len(fragments), middle, len(fragments)-middle, semanticContractMaximumAttempts,
	)
	left, err := recoverSilverSemanticFragmentsWithinBudget(ctx, job, fragments[:middle], model, batchPath+".0", budget)
	if err != nil {
		return silverSemanticRecovery{}, err
	}
	right, err := recoverSilverSemanticFragmentsWithinBudget(ctx, job, fragments[middle:], model, batchPath+".1", budget)
	if err != nil {
		return silverSemanticRecovery{}, err
	}
	if err := left.append(right); err != nil {
		return silverSemanticRecovery{}, err
	}
	return left, nil
}

func logSilverSemanticContractFailure(job silverJob, batchPath string, attempt int, contract *semanticContractError) {
	responseHash := contract.responseHash
	if responseHash == "" {
		responseHash = "unavailable"
	}
	log.Printf(
		"Silver semantic contract failure: job=%s batch=%s attempt=%d/%d expected_ids=%q returned_ids=%q response_sha256=%s error=%v",
		job.ID, batchPath, attempt, semanticContractMaximumAttempts, contract.expectedIDs, contract.returnedIDs,
		responseHash, contract.cause,
	)
}

func compactSemanticPayload(fragment parsedSilverFragment, encoded json.RawMessage) (json.RawMessage, error) {
	payload, ok := fragment.Payload.(map[string]any)
	if !ok || fragment.Kind != "parsed-table-row" || payload["columns"] == nil {
		return encoded, nil
	}
	return json.Marshal(map[string]any{
		"row":     payload["row"],
		"columns": payload["columns"],
	})
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
