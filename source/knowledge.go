package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strings"
)

type silverSemanticBatch struct {
	Fragments []parsedSilverFragment
	Hash      string
}

func (f parsedSilverFragment) identity() string {
	value, _ := json.Marshal(struct {
		Kind     string         `json:"kind"`
		Selector map[string]any `json:"selector"`
		Payload  any            `json:"payload"`
	}{f.Kind, f.Selector, f.Payload})
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
		semanticFragment := strings.TrimSpace(fragment.Text) != ""
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
	for _, fragment := range fragments {
		if strings.TrimSpace(fragment.Text) == "" {
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
		input.Fragments = append(input.Fragments, semanticFragmentInput{
			ID: silverEvidenceForFragment(job, fragment).ID, Kind: fragment.Kind,
			Selector: fragment.Selector, Payload: payload,
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
	if modelID != job.ModelID || modelRevision != job.ModelRevision || model.maximumInputBytes() != job.SemanticInputLimit {
		return silverCheckpoint{}, errors.New("Source model identity or input limit changed during Silver processing")
	}
	if len(input.Fragments) == 0 {
		return checkpoint, nil
	}
	encodedInput, err := json.Marshal(input)
	if err != nil {
		return silverCheckpoint{}, err
	}
	if len(encodedInput) > model.maximumInputBytes() {
		checkpoint.SemanticSkipReason = silverSkipFragmentTooLarge
		return checkpoint, nil
	}
	semanticFragments := make([]parsedSilverFragment, 0, len(fragments))
	for _, fragment := range fragments {
		if strings.TrimSpace(fragment.Text) != "" {
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

func recoverSilverSemanticFragments(ctx context.Context, job silverJob, fragments []parsedSilverFragment, model semanticModel, batchPath string) (silverSemanticRecovery, error) {
	budget := silverSemanticRecoveryBudget{RemainingFragments: semanticMaximumFragments, RemainingCandidates: semanticMaximumCandidates}
	return recoverSilverSemanticFragmentsWithinBudget(ctx, job, fragments, model, batchPath, &budget)
}

func recoverSilverSemanticFragmentsWithinBudget(ctx context.Context, job silverJob, fragments []parsedSilverFragment, model semanticModel, batchPath string, budget *silverSemanticRecoveryBudget) (silverSemanticRecovery, error) {
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
		logSilverSemanticContractFailure(job, batchPath, attempt, newSemanticContractError(input, result, responseHash, contractCause))
	}
	if len(fragments) == 1 {
		log.Printf("Silver semantic contract recovery: job=%s batch=%s action=skip_fragment fragment_id=%q attempts=%d",
			job.ID, batchPath, input.Fragments[0].ID, semanticContractMaximumAttempts)
		return silverSemanticRecovery{SkippedContractFragment: true}, nil
	}
	middle := len(fragments) / 2
	log.Printf("Silver semantic contract recovery: job=%s batch=%s action=split fragments=%d left=%d right=%d attempts=%d",
		job.ID, batchPath, len(fragments), middle, len(fragments)-middle, semanticContractMaximumAttempts)
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
	log.Printf("Silver semantic contract failure: job=%s batch=%s attempt=%d/%d expected_ids=%q returned_ids=%q response_sha256=%s error=%v",
		job.ID, batchPath, attempt, semanticContractMaximumAttempts, contract.expectedIDs, contract.returnedIDs, responseHash, contract.cause)
}

func compactSemanticPayload(fragment parsedSilverFragment, encoded json.RawMessage) (json.RawMessage, error) {
	payload, ok := fragment.Payload.(map[string]any)
	if !ok || fragment.Kind != "parsed-table-row" || payload["columns"] == nil {
		return encoded, nil
	}
	return json.Marshal(map[string]any{"row": payload["row"], "columns": payload["columns"]})
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
		if record.Normalized != normalized || record.TypeAmbiguous || record.Type != entityType {
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
