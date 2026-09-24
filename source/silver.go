package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
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
	silverSchemaVersion     = 1
	silverProcessorID       = "source.silver.pipeline"
	silverExtractionID      = "source.silver.format-extraction"
	silverExtractionVersion = "1"
	silverResolverID        = "source.silver.candidate-resolver"
	silverResolverVersion   = "2"
	silverProcessorVersion  = "3-" + silverExtractionVersion + "-" + semanticProcessorVersion + "-" + silverResolverVersion
	silverResolutionMinimum = 0.70
	silverMaximumBatchBytes = 4096
	silverInspectionBytes   = 8192
	silverMaximumInputBytes = 8 * 1024 * 1024
	silverReconcileInterval = time.Second
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

type silverSource struct {
	BronzeSourceID      string   `json:"bronze_source_id"`
	BronzeContentSHA256 string   `json:"bronze_content_sha256"`
	Title               string   `json:"title"`
	Mime                string   `json:"mime"`
	ProcessorID         string   `json:"processor_id"`
	ProcessorVersion    string   `json:"processor_version"`
	ModelID             string   `json:"model_id,omitempty"`
	ModelRevision       string   `json:"model_revision,omitempty"`
	EvidenceIDs         []string `json:"evidence_ids"`
	ObservationIDs      []string `json:"observation_ids"`
	EntityIDs           []string `json:"entity_ids"`
	ClaimIDs            []string `json:"claim_ids"`
	Stale               bool     `json:"stale,omitempty"`
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
	BatchIndex    int                           `json:"batch_index"`
	BatchSHA256   string                        `json:"batch_sha256"`
	Evidence      []silverEvidence              `json:"evidence"`
	Observations  []silverObservation           `json:"observations"`
	Entities      []silverEntityCandidate       `json:"entity_candidates,omitempty"`
	Attributes    []silverAttributeCandidate    `json:"attribute_candidates,omitempty"`
	Relationships []silverRelationshipCandidate `json:"relationship_candidates,omitempty"`
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
	State               string             `json:"state"`
	TotalBatches        int                `json:"total_batches"`
	Checkpoints         []silverCheckpoint `json:"checkpoints,omitempty"`
	Error               string             `json:"error,omitempty"`
	Attempts            int                `json:"attempts,omitempty"`
	RetryAt             int64              `json:"retry_at,omitempty"`
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
	NextJob       int64                         `json:"next_job"`
	Jobs          []silverJob                   `json:"jobs"`
	Published     map[string]silverDataset      `json:"published"`
	History       map[string][]silverDataset    `json:"history"`
	Entities      map[string]silverEntityRecord `json:"entities"`
}

type silverService struct {
	mu              sync.Mutex
	path            string
	bronze          *bronzeStore
	state           silverDiskState
	persisted       []byte
	wake            chan struct{}
	semantic        semanticModel
	afterCheckpoint func(string, int)
}

func newSilverService(dir string, bronze *bronzeStore) (*silverService, error) {
	model, err := semanticModelFromEnvironment()
	if err != nil {
		return nil, err
	}
	return newSilverServiceWithModel(dir, bronze, model)
}

func newSilverServiceWithModel(dir string, bronze *bronzeStore, model semanticModel) (*silverService, error) {
	s := &silverService{path: filepath.Join(dir, "state.json"), bronze: bronze, wake: make(chan struct{}, 1), semantic: model}
	value, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		s.state = silverDiskState{SchemaVersion: silverSchemaVersion, Published: map[string]silverDataset{}, History: map[string][]silverDataset{}, Entities: map[string]silverEntityRecord{}}
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
	s.backfillEntityTypesLocked()
	s.persisted, err = json.Marshal(s.state)
	if err != nil {
		return nil, err
	}
	recovered := false
	for index := range s.state.Jobs {
		if s.state.Jobs[index].State == "running" || s.state.Jobs[index].State == "failed" {
			s.state.Jobs[index].State = "queued"
			s.state.Jobs[index].Error = ""
			s.state.Jobs[index].RetryAt = 0
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
	go func() {
		ticker := time.NewTicker(silverReconcileInterval)
		defer ticker.Stop()
		for {
			_ = s.reconcile()
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

func (s *silverService) signal() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *silverService) modelIdentity() (string, string) {
	if s.semantic == nil {
		return "", ""
	}
	return s.semantic.identity()
}

func (s *silverService) retryDueFailuresLocked(now time.Time) bool {
	changed := false
	for index := range s.state.Jobs {
		job := &s.state.Jobs[index]
		if job.State != "failed" || job.RetryAt == 0 || job.RetryAt > now.UnixMilli() {
			continue
		}
		job.State = "queued"
		job.Error = ""
		job.RetryAt = 0
		job.UpdatedAt = now.UnixMilli()
		changed = true
	}
	return changed
}

// reconcile makes the durable Bronze manifest the source of truth for Silver
// work. It repairs missed queue entries and requeues transient failures when due.
func (s *silverService) reconcile() error {
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
	modelID, modelRevision := s.modelIdentity()
	if published, ok := s.state.Published[item.ID]; ok && s.sourceMatchesCurrent(published.Source, item) {
		return false
	}
	for _, job := range s.state.Jobs {
		if job.State != "cancelled" && silverJobMatchesItem(job, item, modelID, modelRevision) {
			return false
		}
	}
	return true
}

func silverJobMatchesItem(job silverJob, item bronzeItem, modelID, modelRevision string) bool {
	return job.BronzeSourceID == item.ID && job.BronzeContentSHA256 == item.Hash &&
		job.Title == item.Title && job.Mime == item.Mime &&
		job.ProcessorID == silverProcessorID && job.ProcessorVersion == silverProcessorVersion &&
		job.ModelID == modelID && job.ModelRevision == modelRevision
}

func silverSourceMatchesItem(source silverSource, item bronzeItem, modelID, modelRevision string) bool {
	return silverSourceMatchesBronze(source, item) &&
		source.ProcessorID == silverProcessorID && source.ProcessorVersion == silverProcessorVersion &&
		source.ModelID == modelID && source.ModelRevision == modelRevision
}

func (s *silverService) sourceMatchesCurrent(source silverSource, item bronzeItem) bool {
	if s.semantic == nil && source.ModelID != "" {
		return silverSourceMatchesBronze(source, item) &&
			source.ProcessorID == silverProcessorID && source.ProcessorVersion == silverProcessorVersion
	}
	modelID, modelRevision := s.modelIdentity()
	return silverSourceMatchesItem(source, item, modelID, modelRevision)
}

func silverSourceMatchesBronze(source silverSource, item bronzeItem) bool {
	return source.BronzeSourceID == item.ID && source.BronzeContentSHA256 == item.Hash &&
		source.Title == item.Title && source.Mime == item.Mime
}

func (s *silverService) enqueue(item bronzeItem) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	modelID, modelRevision := s.modelIdentity()
	changed := false
	for index := range s.state.Jobs {
		job := &s.state.Jobs[index]
		if job.BronzeSourceID == item.ID && !silverJobMatchesItem(*job, item, modelID, modelRevision) && (job.State == "queued" || job.State == "running" || job.State == "failed") {
			job.State = "cancelled"
			job.Checkpoints = nil
			job.UpdatedAt = time.Now().UnixMilli()
			changed = true
		}
	}
	if item.Deleted {
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
		}
		if _, ok := s.state.History[item.ID]; ok {
			delete(s.state.History, item.ID)
			changed = true
		}
		s.pruneEntitiesLocked()
		if changed {
			s.state.Revision++
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
		if job.State != "cancelled" && silverJobMatchesItem(*job, item, modelID, modelRevision) {
			if job.State == "failed" {
				job.State = "queued"
				job.Error = ""
				job.UpdatedAt = time.Now().UnixMilli()
				changed = true
			}
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
		ModelID: modelID, ModelRevision: modelRevision,
		State: "queued", AcceptedAt: now, UpdatedAt: now,
	})
	s.state.Revision++
	if err := s.saveLocked(); err != nil {
		return false, err
	}
	s.signal()
	return true, nil
}

func (s *silverService) processNext(ctx context.Context) bool {
	s.mu.Lock()
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
		s.mu.Unlock()
		return false
	}
	s.mu.Unlock()

	err := s.processJob(ctx, job.ID)
	if err != nil && !errors.Is(err, context.Canceled) {
		s.mu.Lock()
		if current := s.jobLocked(job.ID); current != nil && current.State == "running" {
			current.State = "failed"
			current.Error = err.Error()
			current.Attempts++
			current.RetryAt = time.Now().Add(silverRetryDelay(current.Attempts)).UnixMilli()
			current.UpdatedAt = time.Now().UnixMilli()
			s.state.Revision++
			_ = s.saveLocked()
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
		return err
	}
	fragments, supported, err := parseSilverReader(item, file)
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
	if !supported {
		fragments = nil
	}

	s.mu.Lock()
	job = s.jobLocked(jobID)
	if job == nil || job.State != "running" {
		s.mu.Unlock()
		return context.Canceled
	}
	job.TotalBatches = len(fragments)
	job.UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	if err := s.saveLocked(); err != nil {
		s.mu.Unlock()
		return err
	}
	s.mu.Unlock()

	for batchIndex, fragment := range fragments {
		if err := ctx.Err(); err != nil {
			return err
		}
		batchHash := hashBytes([]byte(fragment.identity()))
		s.mu.Lock()
		job = s.jobLocked(jobID)
		if job == nil || job.State != "running" {
			s.mu.Unlock()
			return context.Canceled
		}
		if checkpointFor(job, batchIndex, batchHash) != nil {
			s.mu.Unlock()
			continue
		}
		s.mu.Unlock()

		checkpoint, err := extractSilverBatch(ctx, copy, fragment, batchIndex, batchHash, s.semantic)
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
	modelID, modelRevision := s.modelIdentity()
	if err != nil || latest.Deleted || !silverJobMatchesItem(*job, latest, modelID, modelRevision) {
		return s.cancelJobLocked(job)
	}
	if len(job.Checkpoints) != job.TotalBatches {
		return errors.New("Silver job is missing checkpoints")
	}

	dataset := silverDataset{Source: silverSource{
		BronzeSourceID: job.BronzeSourceID, BronzeContentSHA256: job.BronzeContentSHA256,
		Title: job.Title, Mime: job.Mime, ProcessorID: job.ProcessorID, ProcessorVersion: job.ProcessorVersion,
		ModelID: job.ModelID, ModelRevision: job.ModelRevision,
	}, PublishedAt: time.Now().UnixMilli()}
	sort.Slice(job.Checkpoints, func(i, j int) bool { return job.Checkpoints[i].BatchIndex < job.Checkpoints[j].BatchIndex })
	entitySeen := map[string]bool{}
	for _, checkpoint := range job.Checkpoints {
		dataset.Evidence = append(dataset.Evidence, checkpoint.Evidence...)
		dataset.Observations = append(dataset.Observations, checkpoint.Observations...)
		s.resolveCheckpointCandidatesLocked(&dataset, checkpoint, entitySeen, job.ModelID, job.ModelRevision)
	}
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
	job.State = "completed"
	job.Checkpoints = nil
	job.Error = ""
	job.Attempts = 0
	job.RetryAt = 0
	job.UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	return s.saveLocked()
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
	sameLabel := 0
	for _, record := range s.state.Entities {
		if record.Normalized != normalized {
			continue
		}
		sameLabel++
		if entityType == "" {
			if sameLabel > 1 || record.Type != "" || record.TypeAmbiguous {
				match = nil
				continue
			}
		} else if record.Type != entityType {
			continue
		}
		if match != nil && match.ID != record.Entity.ID {
			return silverEntity{}, false
		}
		value := record.Entity
		match = &value
	}
	if match != nil && (entityType != "" || sameLabel == 1) {
		return *match, true
	}
	if entityType == "" && sameLabel > 0 {
		return silverEntity{}, false
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
		for _, claim := range dataset.Claims {
			if claim.State != "active" || claim.Predicate != "type" || claim.ObjectEntityID != "" {
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
	if err := os.MkdirAll(filepath.Dir(s.path), 0700); err != nil {
		s.restoreLocked()
		return err
	}
	value, err := json.Marshal(s.state)
	if err != nil {
		s.restoreLocked()
		return err
	}
	if err := savePrivate(s.path, value); err != nil {
		s.restoreLocked()
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
	snapshot := silverSnapshot{SchemaVersion: silverSchemaVersion, Revision: s.state.Revision}
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
		if err != nil || current.Deleted || !silverSourceMatchesBronze(dataset.Source, current) {
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
		snapshot.Processing = append(snapshot.Processing, silverProcessing{BronzeSourceID: job.BronzeSourceID, State: job.State, CompletedBatches: len(job.Checkpoints), TotalBatches: job.TotalBatches, Error: job.Error})
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

func (s *silverService) jobSnapshot() sourceJobSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	snapshot := sourceJobSnapshot{Revision: s.state.Revision}
	for _, job := range s.state.Jobs {
		visible := sourceJob{
			ID: job.ID, Kind: "silver_extraction", Title: job.Title,
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
	Kind     string
	Selector map[string]any
	Excerpt  string
	Payload  any
	Text     string
}

func (f parsedSilverFragment) identity() string {
	value, _ := json.Marshal(struct {
		Kind     string         `json:"kind"`
		Selector map[string]any `json:"selector"`
		Payload  any            `json:"payload"`
	}{f.Kind, f.Selector, f.Payload})
	return string(value)
}

func parseSilverReader(item bronzeItem, reader io.Reader) ([]parsedSilverFragment, bool, error) {
	if !declaredSilverText(item.Mime) && knownBinarySilverInput(item) {
		return nil, false, nil
	}
	buffered := bufio.NewReaderSize(reader, silverInspectionBytes)
	prefix, err := buffered.Peek(silverInspectionBytes)
	if err != nil && !errors.Is(err, io.EOF) && !errors.Is(err, bufio.ErrBufferFull) {
		return nil, false, err
	}
	if !declaredSilverText(item.Mime) && silverPrefixLooksBinary(prefix) {
		return nil, false, nil
	}
	data, err := io.ReadAll(io.LimitReader(buffered, silverMaximumInputBytes+1))
	if err != nil {
		return nil, false, err
	}
	if len(data) > silverMaximumInputBytes {
		return nil, false, nil
	}
	return parseSilverText(item, data)
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
			return nil, true, errors.New("text-like Bronze is not valid UTF-8")
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
		if fragments[index].Selector["kind"] != "utf8-byte-range" {
			continue
		}
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
	var visit func(any, string)
	visit = func(node any, path string) {
		switch typed := node.(type) {
		case map[string]any:
			keys := make([]string, 0, len(typed))
			for key := range typed {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			for _, key := range keys {
				visit(typed[key], path+"/"+strings.ReplaceAll(strings.ReplaceAll(key, "~", "~0"), "/", "~1"))
			}
		case []any:
			for index, child := range typed {
				visit(child, path+"/"+strconv.Itoa(index))
			}
		default:
			encoded, _ := json.Marshal(typed)
			fragments = append(fragments, parsedSilverFragment{Kind: "parsed-json-value", Selector: map[string]any{"kind": "json-pointer", "pointer": path}, Excerpt: path + ": " + string(encoded), Payload: map[string]any{"path": path, "value": typed}, Text: scalarText(typed)})
		}
	}
	visit(value, "")
	return fragments, true
}

func parseCSVFragments(text string) ([]parsedSilverFragment, bool) {
	records, ok := parseCSVRecords(text)
	if !ok || len(records) == 0 {
		return nil, false
	}
	headers := records[0]
	fragments := make([]parsedSilverFragment, 0, len(records))
	fragments = append(fragments, parsedSilverFragment{
		Kind: "parsed-table-header", Selector: map[string]any{"kind": "table-row", "row": 1},
		Excerpt: strings.Join(headers, ", "), Payload: map[string]any{"row": 1, "values": headers},
	})
	for index, record := range records[1:] {
		payload := map[string]any{"row": index + 2, "values": record}
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
		fragments = append(fragments, parsedSilverFragment{Kind: "parsed-table-row", Selector: map[string]any{"kind": "table-row", "row": index + 2}, Excerpt: strings.Join(record, ", "), Payload: payload, Text: strings.Join(record, " ")})
	}
	return fragments, true
}

// parseCSVRecords is a small RFC 4180 reader used here to keep Source's V1
// parser dependency-free. It handles quoted fields, escaped quotes, CRLF and
// newlines inside quoted fields. Malformed CSV falls back to generic text.
func parseCSVRecords(text string) ([][]string, bool) {
	var records [][]string
	var record []string
	var field strings.Builder
	inQuotes := false
	quoted := false
	for index := 0; index < len(text); index++ {
		character := text[index]
		if inQuotes {
			if character == '"' {
				if index+1 < len(text) && text[index+1] == '"' {
					field.WriteByte('"')
					index++
				} else {
					inQuotes = false
					quoted = true
				}
			} else {
				field.WriteByte(character)
			}
			continue
		}
		switch character {
		case '"':
			if field.Len() != 0 || quoted {
				return nil, false
			}
			inQuotes = true
		case ',':
			record = append(record, field.String())
			field.Reset()
			quoted = false
		case '\n':
			record = append(record, strings.TrimSuffix(field.String(), "\r"))
			field.Reset()
			quoted = false
			records = append(records, record)
			record = nil
		default:
			if quoted && character != '\r' {
				return nil, false
			}
			field.WriteByte(character)
		}
	}
	if inQuotes {
		return nil, false
	}
	if field.Len() > 0 || quoted || len(record) > 0 {
		record = append(record, strings.TrimSuffix(field.String(), "\r"))
		records = append(records, record)
	}
	return records, true
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
		fragments = append(fragments, fragmentWithTextRange("text-block", start, trimmedEnd, value, map[string]any{"text": value}))
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
			fragments = append(fragments, fragmentWithTextRange("text-block", trimmedStart, trimmedEnd, value, map[string]any{"text": value}))
		}
		if end == len(text) {
			break
		}
		start = end + 2
	}
	return splitLargeFragments(fragments)
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
		if len(fragment.Text) <= silverMaximumBatchBytes {
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
			output = append(output, fragmentWithTextRange(fragment.Kind, base+consumed, base+consumed+len(part), part, map[string]any{"text": part}))
			remaining = remaining[end:]
			consumed += end
		}
	}
	return output
}

func fragmentWithTextRange(kind string, start, end int, text string, payload any) parsedSilverFragment {
	return parsedSilverFragment{Kind: kind, Selector: map[string]any{"kind": "utf8-byte-range", "start_byte": start, "end_byte": end}, Excerpt: truncate(text, 240), Payload: payload, Text: text}
}

func extractSilverBatch(ctx context.Context, job silverJob, fragment parsedSilverFragment, index int, batchHash string, model semanticModel) (silverCheckpoint, error) {
	producer := silverProducer{ProcessorID: silverExtractionID, ProcessorVersion: silverExtractionVersion}
	evidence := silverEvidence{BronzeSourceID: job.BronzeSourceID, BronzeContentSHA256: job.BronzeContentSHA256, Selector: fragment.Selector, Excerpt: fragment.Excerpt}
	evidence.ID = stableID("source-silver-evidence", struct {
		Source, Hash string
		Selector     map[string]any
	}{job.BronzeSourceID, job.BronzeContentSHA256, fragment.Selector})
	payload, err := json.Marshal(fragment.Payload)
	if err != nil {
		return silverCheckpoint{}, err
	}
	observation := silverObservation{Kind: fragment.Kind, Payload: payload, EvidenceIDs: []string{evidence.ID}, Producer: producer}
	observation.ID = observationID(observation)
	checkpoint := silverCheckpoint{BatchIndex: index, BatchSHA256: batchHash, Evidence: []silverEvidence{evidence}, Observations: []silverObservation{observation}}
	if model == nil || strings.TrimSpace(fragment.Text) == "" {
		return checkpoint, nil
	}
	modelID, modelRevision := model.identity()
	if modelID != job.ModelID || modelRevision != job.ModelRevision {
		return silverCheckpoint{}, errors.New("Source model identity changed during Silver processing")
	}
	result, err := model.extract(ctx, semanticInput{Title: job.Title, Mime: job.Mime, Text: fragment.Text})
	if err != nil {
		return silverCheckpoint{}, err
	}
	semanticProducer := silverProducer{ProcessorID: semanticProcessorID, ProcessorVersion: semanticProcessorVersion, ModelID: modelID, ModelRevision: modelRevision}
	for _, candidate := range result.Entities {
		label := strings.TrimSpace(candidate.Label)
		entityType := strings.TrimSpace(candidate.Type)
		candidate.Label = label
		candidate.Type = entityType
		payload, _ := json.Marshal(map[string]any{"ref": candidate.Ref, "label": label, "type": entityType})
		semantic := silverObservation{Kind: "entity-candidate", Payload: payload, EvidenceIDs: []string{evidence.ID}, Confidence: candidate.Confidence, Producer: semanticProducer}
		semantic.ID = observationID(semantic)
		checkpoint.Observations = append(checkpoint.Observations, semantic)
		checkpoint.Entities = append(checkpoint.Entities, silverEntityCandidate{
			ObservationID: semantic.ID, Ref: candidate.Ref, Label: label, Type: entityType,
			Normalized: strings.ToLower(strings.Join(strings.Fields(label), " ")), Confidence: *candidate.Confidence,
		})
	}
	for _, candidate := range result.Attributes {
		payload, _ := json.Marshal(struct {
			SubjectRef string          `json:"subject_ref"`
			Predicate  string          `json:"predicate"`
			Value      json.RawMessage `json:"value"`
		}{candidate.SubjectRef, candidate.Predicate, candidate.Value})
		semantic := silverObservation{Kind: "attribute-candidate", Payload: payload, EvidenceIDs: []string{evidence.ID}, Confidence: candidate.Confidence, Producer: semanticProducer}
		semantic.ID = observationID(semantic)
		checkpoint.Observations = append(checkpoint.Observations, semantic)
		checkpoint.Attributes = append(checkpoint.Attributes, silverAttributeCandidate{
			ObservationID: semantic.ID, SubjectRef: candidate.SubjectRef, Predicate: candidate.Predicate,
			Value: candidate.Value, Confidence: *candidate.Confidence,
		})
	}
	for _, candidate := range result.Relationships {
		payload, _ := json.Marshal(map[string]any{"subject_ref": candidate.SubjectRef, "predicate": candidate.Predicate, "object_ref": candidate.ObjectRef})
		semantic := silverObservation{Kind: "relationship-candidate", Payload: payload, EvidenceIDs: []string{evidence.ID}, Confidence: candidate.Confidence, Producer: semanticProducer}
		semantic.ID = observationID(semantic)
		checkpoint.Observations = append(checkpoint.Observations, semantic)
		checkpoint.Relationships = append(checkpoint.Relationships, silverRelationshipCandidate{
			ObservationID: semantic.ID, SubjectRef: candidate.SubjectRef, Predicate: candidate.Predicate,
			ObjectRef: candidate.ObjectRef, Confidence: *candidate.Confidence,
		})
	}
	return checkpoint, nil
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
