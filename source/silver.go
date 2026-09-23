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
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	silverSchemaVersion     = 1
	silverProcessorID       = "source.silver.generic-text"
	silverProcessorVersion  = "1"
	silverMaximumBatchBytes = 4096
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
	EvidenceIDs         []string `json:"evidence_ids"`
	ObservationIDs      []string `json:"observation_ids"`
	EntityIDs           []string `json:"entity_ids"`
	ClaimIDs            []string `json:"claim_ids"`
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
}

type silverMention struct {
	ObservationID string `json:"observation_id"`
	Label         string `json:"label"`
	Normalized    string `json:"normalized"`
}

type silverCheckpoint struct {
	BatchIndex   int                 `json:"batch_index"`
	BatchSHA256  string              `json:"batch_sha256"`
	Evidence     []silverEvidence    `json:"evidence"`
	Observations []silverObservation `json:"observations"`
	Mentions     []silverMention     `json:"mentions,omitempty"`
}

type silverJob struct {
	ID                  string             `json:"id"`
	BronzeSourceID      string             `json:"bronze_source_id"`
	BronzeContentSHA256 string             `json:"bronze_content_sha256"`
	Title               string             `json:"title"`
	Mime                string             `json:"mime"`
	ProcessorID         string             `json:"processor_id"`
	ProcessorVersion    string             `json:"processor_version"`
	State               string             `json:"state"`
	TotalBatches        int                `json:"total_batches"`
	Checkpoints         []silverCheckpoint `json:"checkpoints,omitempty"`
	Error               string             `json:"error,omitempty"`
	AcceptedAt          int64              `json:"accepted_at"`
	UpdatedAt           int64              `json:"updated_at"`
}

type silverEntityRecord struct {
	Entity     silverEntity `json:"entity"`
	Label      string       `json:"label"`
	Normalized string       `json:"normalized"`
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
	afterCheckpoint func(string, int)
}

func newSilverService(dir string, bronze *bronzeStore) (*silverService, error) {
	s := &silverService{path: filepath.Join(dir, "state.json"), bronze: bronze, wake: make(chan struct{}, 1)}
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
	s.persisted, err = json.Marshal(s.state)
	if err != nil {
		return nil, err
	}
	recovered := false
	for index := range s.state.Jobs {
		if s.state.Jobs[index].State == "running" || s.state.Jobs[index].State == "failed" {
			s.state.Jobs[index].State = "queued"
			s.state.Jobs[index].Error = ""
			recovered = true
		}
	}
	if recovered {
		s.state.Revision++
		if err := s.saveLocked(); err != nil {
			return nil, err
		}
	}
	items, err := bronze.manifest()
	if err != nil {
		return nil, err
	}
	for _, item := range items {
		if _, err := s.enqueue(item); err != nil {
			return nil, err
		}
	}
	return s, nil
}

func (s *silverService) start(ctx context.Context) {
	go func() {
		for {
			for s.processNext(ctx) {
			}
			select {
			case <-ctx.Done():
				return
			case <-s.wake:
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

func (s *silverService) enqueue(item bronzeItem) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	changed := false
	for index := range s.state.Jobs {
		job := &s.state.Jobs[index]
		if job.BronzeSourceID == item.ID && job.BronzeContentSHA256 != item.Hash && (job.State == "queued" || job.State == "running" || job.State == "failed") {
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
	if published, ok := s.state.Published[item.ID]; ok && published.Source.BronzeContentSHA256 == item.Hash &&
		published.Source.ProcessorID == silverProcessorID && published.Source.ProcessorVersion == silverProcessorVersion {
		if published.Source.Title != item.Title || published.Source.Mime != item.Mime {
			published.Source.Title = item.Title
			published.Source.Mime = item.Mime
			s.state.Published[item.ID] = published
			changed = true
		}
		if changed {
			s.state.Revision++
			return true, s.saveLocked()
		}
		return false, nil
	}
	for index := range s.state.Jobs {
		job := &s.state.Jobs[index]
		if job.BronzeSourceID == item.ID && job.BronzeContentSHA256 == item.Hash && job.ProcessorID == silverProcessorID && job.ProcessorVersion == silverProcessorVersion && job.State != "cancelled" {
			if job.Title != item.Title || job.Mime != item.Mime {
				job.Title = item.Title
				job.Mime = item.Mime
				job.UpdatedAt = time.Now().UnixMilli()
				changed = true
			}
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
			current.UpdatedAt = time.Now().UnixMilli()
			s.state.Revision++
			_ = s.saveLocked()
		}
		s.mu.Unlock()
	}
	return ctx.Err() == nil
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
	data, err := io.ReadAll(file)
	_ = file.Close()
	if err != nil {
		return err
	}
	if item.Hash != copy.BronzeContentSHA256 {
		return s.cancelJob(jobID)
	}
	fragments, supported, err := parseSilverText(item, data)
	if err != nil {
		return err
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

		checkpoint, err := extractSilverBatch(copy, fragment, batchIndex, batchHash)
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
	if err != nil || latest.Deleted || latest.Hash != job.BronzeContentSHA256 {
		return s.cancelJobLocked(job)
	}
	if len(job.Checkpoints) != job.TotalBatches {
		return errors.New("Silver job is missing checkpoints")
	}

	dataset := silverDataset{Source: silverSource{
		BronzeSourceID: job.BronzeSourceID, BronzeContentSHA256: job.BronzeContentSHA256,
		Title: job.Title, Mime: job.Mime, ProcessorID: job.ProcessorID, ProcessorVersion: job.ProcessorVersion,
	}, PublishedAt: time.Now().UnixMilli()}
	sort.Slice(job.Checkpoints, func(i, j int) bool { return job.Checkpoints[i].BatchIndex < job.Checkpoints[j].BatchIndex })
	var mentions []silverMention
	for _, checkpoint := range job.Checkpoints {
		dataset.Evidence = append(dataset.Evidence, checkpoint.Evidence...)
		dataset.Observations = append(dataset.Observations, checkpoint.Observations...)
		mentions = append(mentions, checkpoint.Mentions...)
	}
	producer := silverProducer{ProcessorID: "source.silver.exact-label-resolver", ProcessorVersion: "1"}
	entitySeen := map[string]bool{}
	for _, mention := range mentions {
		entity, ok := s.resolveEntityLocked(mention.Label, mention.Normalized)
		if !ok {
			continue
		}
		if !entitySeen[entity.ID] {
			dataset.Entities = append(dataset.Entities, entity)
			entitySeen[entity.ID] = true
		}
		value, _ := json.Marshal(mention.Label)
		claim := silverClaim{SubjectEntityID: entity.ID, Predicate: "name", Value: value, SupportingObservationIDs: []string{mention.ObservationID}, Producer: producer, State: "active"}
		claim.ID = stableID("source-silver-claim", struct {
			Subject      string          `json:"subject"`
			Predicate    string          `json:"predicate"`
			Value        json.RawMessage `json:"value"`
			Observations []string        `json:"observations"`
			Producer     silverProducer  `json:"producer"`
		}{entity.ID, claim.Predicate, claim.Value, claim.SupportingObservationIDs, producer})
		dataset.Claims = append(dataset.Claims, claim)
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
	job.UpdatedAt = time.Now().UnixMilli()
	s.state.Revision++
	return s.saveLocked()
}

func (s *silverService) resolveEntityLocked(label, normalized string) (silverEntity, bool) {
	var match *silverEntity
	for _, record := range s.state.Entities {
		if record.Normalized != normalized {
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
	s.state.Entities[id] = silverEntityRecord{Entity: entity, Label: label, Normalized: normalized}
	return entity, true
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
		if err != nil || current.Deleted || current.Hash != dataset.Source.BronzeContentSHA256 {
			continue
		}
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

func parseSilverText(item bronzeItem, data []byte) ([]parsedSilverFragment, bool, error) {
	if len(data) == 0 {
		return []parsedSilverFragment{}, true, nil
	}
	if bytes.HasPrefix(data, []byte{0xef, 0xbb, 0xbf}) {
		data = data[3:]
	}
	declaredText := strings.HasPrefix(item.Mime, "text/") || item.Mime == "application/json" || item.Mime == "application/csv"
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
	if item.Mime == "application/json" || strings.HasSuffix(lowerName, ".json") {
		if fragments, ok := parseJSONFragments(text); ok {
			return fragments, true, nil
		}
	}
	if item.Mime == "text/csv" || item.Mime == "application/csv" || strings.HasSuffix(lowerName, ".csv") {
		if fragments, ok := parseCSVFragments(text); ok {
			return fragments, true, nil
		}
	}
	if item.Mime == "text/markdown" || strings.HasSuffix(lowerName, ".md") || strings.HasSuffix(lowerName, ".markdown") {
		if fragments := parseMarkdownFragments(text); len(fragments) > 0 {
			return fragments, true, nil
		}
	}
	return parseGenericFragments(text), true, nil
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
		value := strings.TrimSpace(strings.Join(paragraph, "\n"))
		fragments = append(fragments, fragmentWithTextRange("text-block", paragraphStart, end, value, map[string]any{"text": value}))
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
				fragments = append(fragments, fragmentWithTextRange("markdown-heading", offset, offset+len(lineWithNewline), value, map[string]any{"level": prefix, "text": value}))
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
		value := strings.TrimSpace(text[start:end])
		if value != "" {
			fragments = append(fragments, fragmentWithTextRange("text-block", start, end, value, map[string]any{"text": value}))
		}
		if end == len(text) {
			break
		}
		start = end + 2
	}
	return splitLargeFragments(fragments)
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

func extractSilverBatch(job silverJob, fragment parsedSilverFragment, index int, batchHash string) (silverCheckpoint, error) {
	producer := silverProducer{ProcessorID: job.ProcessorID, ProcessorVersion: job.ProcessorVersion}
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
	semanticText := strings.Join(strings.Fields(fragment.Text), " ")
	if semanticText != "" {
		semanticPayload, _ := json.Marshal(map[string]any{"statement": semanticText})
		semantic := silverObservation{Kind: "semantic-statement", Payload: semanticPayload, EvidenceIDs: []string{evidence.ID}, Producer: producer}
		semantic.ID = observationID(semantic)
		checkpoint.Observations = append(checkpoint.Observations, semantic)
	}
	seen := map[string]bool{}
	for _, label := range genericEntityMentions(fragment.Text) {
		normalized := strings.ToLower(strings.Join(strings.Fields(label), " "))
		if seen[normalized] {
			continue
		}
		seen[normalized] = true
		mentionPayload, _ := json.Marshal(map[string]any{"label": label})
		mention := silverObservation{Kind: "entity-mention", Payload: mentionPayload, EvidenceIDs: []string{evidence.ID}, Producer: producer}
		mention.ID = observationID(mention)
		checkpoint.Observations = append(checkpoint.Observations, mention)
		checkpoint.Mentions = append(checkpoint.Mentions, silverMention{ObservationID: mention.ID, Label: label, Normalized: normalized})
	}
	return checkpoint, nil
}

var entityMentionPattern = regexp.MustCompile(`\b\p{Lu}[\p{L}\p{M}'’-]*(?:[ \t]+\p{Lu}[\p{L}\p{M}'’-]*)+\b`)

func genericEntityMentions(text string) []string {
	matches := entityMentionPattern.FindAllString(text, -1)
	result := make([]string, 0, len(matches))
	for _, match := range matches {
		value := strings.TrimSpace(match)
		if len([]rune(value)) <= 120 {
			result = append(result, value)
		}
	}
	return result
}

func observationID(observation silverObservation) string {
	return stableID("source-silver-observation", struct {
		Kind     string          `json:"kind"`
		Payload  json.RawMessage `json:"payload"`
		Evidence []string        `json:"evidence"`
		Producer silverProducer  `json:"producer"`
	}{observation.Kind, observation.Payload, observation.EvidenceIDs, observation.Producer})
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
