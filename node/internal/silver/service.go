package silver

import (
	"context"
	"crypto/md5"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	localai "source.local/node/internal/ai"
	"source.local/node/internal/apperror"
	"source.local/node/internal/database"
	"source.local/node/internal/storage"
	"source.local/node/internal/syncmodel"
)

type Source struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	SourceType     string `json:"sourceType"`
	ContentSHA256  string `json:"contentSha256"`
	Text           string `json:"text"`
	AuthoredBySelf bool   `json:"authoredBySelf,omitempty"`
}

type RefineRequest struct {
	ContractVersion int    `json:"contractVersion"`
	OperationID     string `json:"operationId"`
	Source          Source `json:"source"`
}

type RefineResult struct {
	BronzeAccepted bool `json:"bronzeAccepted"`
	Job            Job  `json:"job"`
}

type RemoveRequest struct {
	ContractVersion int    `json:"contractVersion"`
	OperationID     string `json:"operationId"`
	SourceID        string `json:"sourceId"`
}

type RemoveResult struct {
	BronzeRemoved bool                     `json:"bronzeRemoved"`
	SilverChanged bool                     `json:"silverChanged"`
	Receipt       *syncmodel.CommitReceipt `json:"receipt,omitempty"`
}

type Service struct {
	db              *database.DB
	storage         *storage.Storage
	ai              localai.Backend
	maximumBytes    int64
	now             func() time.Time
	mu              sync.Mutex
	workerCtx       context.Context
	stopWorker      context.CancelFunc
	wake            chan struct{}
	workerDone      chan struct{}
	runningMu       sync.Mutex
	runningUserID   string
	runningJobID    string
	runningSourceID string
	runningStop     context.CancelFunc
}

func New(db *database.DB, storage *storage.Storage, ai localai.Backend, maximumBytes int64, now func() time.Time) *Service {
	workerCtx, stopWorker := context.WithCancel(context.Background())
	service := &Service{
		db: db, storage: storage, ai: ai, maximumBytes: maximumBytes, now: now,
		workerCtx: workerCtx, stopWorker: stopWorker, wake: make(chan struct{}, 1), workerDone: make(chan struct{}),
	}
	go service.work()
	service.signalWorker()
	return service
}

func (s *Service) Refine(_ context.Context, userID string, request RefineRequest) (RefineResult, error) {
	if err := validateRequest(request); err != nil {
		return RefineResult{}, err
	}
	requestBytes, err := marshalCanonical(request)
	if err != nil {
		return RefineResult{}, err
	}
	if err = s.checkQuota(userID, request.Source); err != nil {
		return RefineResult{}, err
	}
	digest := sha256.Sum256(requestBytes)
	now := s.now().UnixMilli()
	modelID, _ := s.ai.Capabilities()["modelId"].(string)
	if modelID == "" {
		return RefineResult{}, apperror.New(503, "silver_model_unavailable", "The Node has no Silver refinement model.")
	}
	chunks := textChunks(request.Source.Text, maximumChunkBytes, chunkOverlapBytes)
	s.mu.Lock()
	job, changed, err := s.db.AcceptSilverRefinementJob(userID, request.OperationID,
		hex.EncodeToString(digest[:]), database.SilverRefinementSource{
			SourceID: request.Source.ID, Name: request.Source.Name, SourceType: request.Source.SourceType,
			ContentSHA256: request.Source.ContentSHA256, Plaintext: request.Source.Text,
			AuthoredBySelf: request.Source.AuthoredBySelf, AcceptedAt: now,
		}, ExtractionProcessorID, ExtractionVersion, modelID, "silver", len(chunks), now)
	s.mu.Unlock()
	if err != nil {
		return RefineResult{}, mapDatabaseError(err)
	}
	s.signalWorker()
	return RefineResult{BronzeAccepted: changed, Job: wireJob(job)}, nil
}

func (s *Service) Remove(userID string, request RemoveRequest) (RemoveResult, error) {
	if request.ContractVersion != 1 || !syncmodel.ValidUUID(request.OperationID) || !validOpenText(request.SourceID, 240) {
		return RemoveResult{}, apperror.New(400, "invalid_silver_removal", "The Silver removal request is invalid.")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	requestBytes, err := marshalCanonical(request)
	if err != nil {
		return RemoveResult{}, err
	}
	digest := sha256.Sum256(requestBytes)
	now := s.now().UnixMilli()
	removed, applySilver, err := s.db.RemoveSilverRefinementSource(userID, request.OperationID,
		hex.EncodeToString(digest[:]), request.SourceID, now)
	if err != nil {
		return RemoveResult{}, mapDatabaseError(err)
	}
	if removed {
		s.cancelRunningSource(userID, request.SourceID)
	}
	if !applySilver {
		return RemoveResult{BronzeRemoved: removed}, nil
	}
	dataset, err := s.currentDataset(userID)
	if err != nil {
		return RemoveResult{}, err
	}
	before, err := EncodeDataset(dataset)
	if err != nil {
		return RemoveResult{}, err
	}
	updated := removeSource(dataset, request.SourceID, now)
	after, err := EncodeDataset(updated)
	if err != nil {
		return RemoveResult{}, err
	}
	if string(before) == string(after) {
		if err = s.db.MarkSilverRemovalComplete(userID, request.OperationID, now); err != nil {
			return RemoveResult{}, err
		}
		return RemoveResult{BronzeRemoved: removed}, nil
	}
	node, err := s.db.GetNodeState(false)
	if err != nil {
		return RemoveResult{}, err
	}
	receipt, _, created, err := s.storage.CommitNodeValue(
		userID, node.NodeID, Collection, canonicalObjectID(Collection), DatasetFormat,
		DatasetFormatVersion, after, updated.ModifiedAtMillis, s.maximumBytes,
	)
	if err != nil {
		return RemoveResult{}, err
	}
	if err = s.db.MarkSilverRemovalComplete(userID, request.OperationID, now); err != nil {
		return RemoveResult{}, err
	}
	return RemoveResult{BronzeRemoved: removed, SilverChanged: created, Receipt: &receipt}, nil
}

func (s *Service) cancelRunningSource(userID, sourceID string) {
	s.runningMu.Lock()
	defer s.runningMu.Unlock()
	if s.runningUserID == userID && s.runningSourceID == sourceID && s.runningStop != nil {
		s.runningStop()
	}
}

func (s *Service) checkQuota(userID string, source Source) error {
	legacyBytes, err := s.db.TotalStorageBytes(userID)
	if err != nil {
		return err
	}
	canonicalBytes, err := s.db.CanonicalStorageBytes(userID)
	if err != nil {
		return err
	}
	bronzeBytes, err := s.db.SilverRefinementBytesExcept(userID, source.ID)
	if err != nil {
		return err
	}
	user, err := s.db.FindUser(userID)
	if err != nil {
		return err
	}
	if user == nil {
		return apperror.New(404, "user_not_found", "User storage namespace is unavailable.")
	}
	if legacyBytes+canonicalBytes+bronzeBytes+int64(len([]byte(source.Text))) > user.QuotaBytes {
		return apperror.New(413, "storage_quota_exceeded", "User storage quota exceeded")
	}
	return nil
}

func (s *Service) checkFinalQuota(userID string, payloadBytes int64) error {
	legacyBytes, err := s.db.TotalStorageBytes(userID)
	if err != nil {
		return err
	}
	canonicalBytes, err := s.db.CanonicalStorageBytes(userID)
	if err != nil {
		return err
	}
	bronzeBytes, err := s.db.SilverRefinementBytes(userID)
	if err != nil {
		return err
	}
	user, err := s.db.FindUser(userID)
	if err != nil {
		return err
	}
	if user == nil {
		return apperror.New(404, "user_not_found", "User storage namespace is unavailable.")
	}
	if legacyBytes+canonicalBytes+bronzeBytes+payloadBytes > user.QuotaBytes {
		return apperror.New(413, "storage_quota_exceeded", "User storage quota exceeded")
	}
	return nil
}

func (s *Service) currentDataset(userID string) (Dataset, error) {
	heads, err := s.db.SyncHeads(userID, Collection, canonicalObjectID(Collection))
	if err != nil {
		return Dataset{}, err
	}
	if len(heads) == 0 {
		return EmptyDataset(), nil
	}
	if len(heads) != 1 || heads[0].Kind != "content" {
		return Dataset{}, apperror.New(409, "silver_history_conflict", "The authoritative Silver history is not a single content head.")
	}
	value, _, err := s.storage.CanonicalPayload(userID, heads[0].RevisionID)
	if err != nil {
		return Dataset{}, err
	}
	if value == nil {
		return Dataset{}, errors.New("authoritative Silver payload is missing")
	}
	dataset, err := DecodeDataset(value.Body)
	if err != nil {
		return Dataset{}, apperror.Wrap(500, "silver_payload_invalid", "The authoritative Silver payload is invalid.", err)
	}
	return dataset, validateDataset(dataset)
}

func generationComplete(dataset Dataset, sourceID, contentSHA, modelID string, authoredBySelf bool) bool {
	evidence := map[string]bool{}
	for _, item := range dataset.Evidence {
		if item.BronzeSourceID == sourceID && item.BronzeContentSHA256 == contentSHA {
			evidence[item.ID] = true
		}
	}
	for _, item := range dataset.Observations {
		if item.Kind != ExtractionCompleteKind || item.Producer.ProcessorID != ExtractionProcessorID ||
			item.Producer.ProcessorVersion != ExtractionVersion || item.Producer.ModelID == nil || *item.Producer.ModelID != modelID {
			continue
		}
		payload, ok := item.Payload.(map[string]any)
		if !ok || payload["authoredBySelf"] != authoredBySelf {
			continue
		}
		for _, evidenceID := range item.EvidenceIDs {
			if evidence[evidenceID] {
				return true
			}
		}
	}
	return false
}

func replaceGeneration(dataset Dataset, profile ProfileContext, source Source, generation extractedGeneration, now int64) (Dataset, error) {
	if now <= dataset.ModifiedAtMillis {
		now = dataset.ModifiedAtMillis + 1
	}
	for index := range generation.Observations {
		generation.Observations[index].CreatedAtMillis = now
	}
	var err error
	dataset, err = ensureProfileIdentity(dataset, profile, now)
	if err != nil {
		return Dataset{}, err
	}
	oldEvidence := map[string]Evidence{}
	for _, item := range dataset.Evidence {
		oldEvidence[item.ID] = item
	}
	oldObservations := map[string]Observation{}
	for _, item := range dataset.Observations {
		oldObservations[item.ID] = item
	}
	dataset.Evidence = mergeEvidence(dataset.Evidence, generation.Evidence)
	dataset.Observations = mergeObservations(dataset.Observations, generation.Observations)
	resolutionInputs := candidateObservations(dataset, generation.Observations)
	resolvedEntities, resolvedClaims, err := resolve(dataset, resolutionInputs, profile, now)
	if err != nil {
		return Dataset{}, err
	}
	freshClaims := map[string]bool{}
	for _, claim := range resolvedClaims {
		freshClaims[claim.ID] = true
	}
	for index := range dataset.Claims {
		claim := &dataset.Claims[index]
		if claim.State != "active" || freshClaims[claim.ID] {
			continue
		}
		for _, observationID := range claim.SupportingObservationIDs {
			observation, ok := oldObservations[observationID]
			if !ok || observation.Producer.ProcessorID != ExtractionProcessorID {
				continue
			}
			for _, evidenceID := range observation.EvidenceIDs {
				if oldEvidence[evidenceID].BronzeSourceID == source.ID {
					claim.State = "superseded"
				}
			}
		}
	}
	dataset.Entities = mergeEntities(dataset.Entities, resolvedEntities)
	dataset.Claims = mergeClaims(dataset.Claims, resolvedClaims)
	strengthened, err := strengthenClaims(dataset, now)
	if err != nil {
		return Dataset{}, err
	}
	dataset.Claims = mergeClaims(dataset.Claims, strengthened)
	dataset.ModifiedAtMillis = now
	delete(dataset.RemovedSources, source.ID)
	return dataset, validateDataset(dataset)
}

func mergeEvidence(stored, fresh []Evidence) []Evidence {
	items := map[string]Evidence{}
	for _, item := range stored {
		items[item.ID] = item
	}
	for _, item := range fresh {
		if prior, ok := items[item.ID]; !ok || (prior.Excerpt == "" && item.Excerpt != "") {
			items[item.ID] = item
		}
	}
	result := make([]Evidence, 0, len(items))
	for _, item := range items {
		result = append(result, item)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result
}
func mergeObservations(stored, fresh []Observation) []Observation {
	items := map[string]Observation{}
	for _, item := range stored {
		items[item.ID] = item
	}
	for _, item := range fresh {
		if prior, ok := items[item.ID]; !ok || item.CreatedAtMillis < prior.CreatedAtMillis {
			items[item.ID] = item
		}
	}
	result := make([]Observation, 0, len(items))
	for _, item := range items {
		result = append(result, item)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result
}
func mergeEntities(stored, fresh []Entity) []Entity {
	items := map[string]Entity{}
	for _, item := range stored {
		items[item.ID] = item
	}
	for _, item := range fresh {
		items[item.ID] = item
	}
	result := make([]Entity, 0, len(items))
	for _, item := range items {
		result = append(result, item)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result
}
func mergeClaims(stored, fresh []Claim) []Claim {
	items := map[string]Claim{}
	for _, item := range stored {
		items[item.ID] = item
	}
	for _, item := range fresh {
		if prior, ok := items[item.ID]; ok && prior.State != "active" {
			item.State = prior.State
		}
		items[item.ID] = item
	}
	result := make([]Claim, 0, len(items))
	for _, item := range items {
		result = append(result, item)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result
}

func removeSource(dataset Dataset, sourceID string, now int64) Dataset {
	if prior := dataset.RemovedSources[sourceID]; prior > 0 {
		return dataset
	}
	if now <= dataset.ModifiedAtMillis {
		now = dataset.ModifiedAtMillis + 1
	}
	removedEvidence := map[string]bool{}
	var evidence []Evidence
	for _, item := range dataset.Evidence {
		if item.BronzeSourceID == sourceID {
			removedEvidence[item.ID] = true
		} else {
			evidence = append(evidence, item)
		}
	}
	removedObservations := map[string]bool{}
	var observations []Observation
	for _, item := range dataset.Observations {
		remove := false
		for _, evidenceID := range item.EvidenceIDs {
			if removedEvidence[evidenceID] {
				remove = true
			}
		}
		if remove {
			removedObservations[item.ID] = true
		} else {
			observations = append(observations, item)
		}
	}
	var claims []Claim
	for _, item := range dataset.Claims {
		remove := false
		for _, observationID := range item.SupportingObservationIDs {
			if removedObservations[observationID] {
				remove = true
			}
		}
		if !remove {
			claims = append(claims, item)
		}
	}
	referencedEntities := map[string]bool{}
	for _, item := range claims {
		referencedEntities[item.SubjectEntityID] = true
		if item.ObjectEntityID != nil {
			referencedEntities[*item.ObjectEntityID] = true
		}
	}
	var entities []Entity
	for _, item := range dataset.Entities {
		if referencedEntities[item.ID] {
			entities = append(entities, item)
		}
	}
	removedSources := make(map[string]int64, len(dataset.RemovedSources)+1)
	for key, value := range dataset.RemovedSources {
		removedSources[key] = value
	}
	dataset.RemovedSources = removedSources
	dataset.Evidence, dataset.Observations = evidence, observations
	dataset.Claims, dataset.Entities = claims, entities
	dataset.RemovedSources[sourceID] = now
	dataset.ModifiedAtMillis = now
	return dataset
}

func canonicalObjectID(collection string) string {
	digest := md5.Sum([]byte("source-storage-object\x00" + collection))
	digest[6] = (digest[6] & 0x0f) | 0x30
	digest[8] = (digest[8] & 0x3f) | 0x80
	hexID := hex.EncodeToString(digest[:])
	return fmt.Sprintf("%s-%s-%s-%s-%s", hexID[:8], hexID[8:12], hexID[12:16], hexID[16:20], hexID[20:])
}

var shaPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

func validateRequest(request RefineRequest) error {
	source := request.Source
	if request.ContractVersion != 1 || !syncmodel.ValidUUID(request.OperationID) ||
		!validOpenText(source.ID, 240) || !validOpenText(source.Name, 255) || !validOpenText(source.SourceType, 64) ||
		!shaPattern.MatchString(source.ContentSHA256) || strings.TrimSpace(source.Text) == "" {
		return apperror.New(400, "invalid_silver_refinement", "The Silver refinement request is invalid.")
	}
	if len([]byte(source.Text)) > maximumRefinementBytes {
		return apperror.New(413, "silver_source_too_large", "The Bronze source is too large for one bounded refinement request.")
	}
	digest := sha256.Sum256([]byte(source.Text))
	if hex.EncodeToString(digest[:]) != source.ContentSHA256 {
		return apperror.New(400, "bronze_hash_mismatch", "The Bronze text does not match its content identity.")
	}
	return nil
}

func mapDatabaseError(err error) error {
	var failure database.SyncFailure
	if errors.As(err, &failure) {
		return apperror.New(409, failure.Code, failure.Message)
	}
	return err
}
