package silver

import (
	"errors"
	"sort"
)

type ProfileContext struct {
	UserID, DisplayName, SelfEntityID string
}

func ensureProfileIdentity(dataset Dataset, context ProfileContext, now int64) (Dataset, error) {
	if context.UserID == "" || context.DisplayName == "" || context.SelfEntityID == "" {
		return Dataset{}, errors.New("Silver profile identity is incomplete")
	}
	evidence, err := newEvidence(
		"source-profile:"+context.UserID,
		sha256Text(context.DisplayName),
		map[string]any{"field": "displayName", "kind": "profile-field"},
		context.DisplayName,
	)
	if err != nil {
		return Dataset{}, err
	}
	producer := Producer{ProcessorID: ProfileProcessorID, ProcessorVersion: ProfileProcessorVersion}
	observation, err := NewObservation("profile-identity", map[string]any{
		"entityId": context.SelfEntityID,
		"name":     context.DisplayName,
		"type":     "person",
	}, []string{evidence.ID}, nil, producer, now)
	if err != nil {
		return Dataset{}, err
	}
	claims := make([]Claim, 0, 2)
	for predicate, value := range map[string]string{"name": context.DisplayName, "entity-type": "person"} {
		claim, createErr := NewClaim(context.SelfEntityID, predicate, nil, value, []string{observation.ID}, nil, producer, now)
		if createErr != nil {
			return Dataset{}, createErr
		}
		claims = append(claims, claim)
	}
	dataset.Evidence = mergeEvidence(dataset.Evidence, []Evidence{evidence})
	dataset.Observations = mergeObservations(dataset.Observations, []Observation{observation})
	dataset.Entities = mergeEntities(dataset.Entities, []Entity{{ID: context.SelfEntityID}})
	dataset.Claims = mergeClaims(dataset.Claims, claims)
	return dataset, nil
}

func candidateObservations(dataset Dataset, fresh []Observation) []Observation {
	inputs := map[string]Observation{}
	for _, observation := range fresh {
		if candidatePredicate(observation) != "" {
			inputs[observation.ID] = observation
		}
	}
	currentEvidence := currentExtractionEvidence(dataset)
	for _, observation := range dataset.Observations {
		predicate := candidatePredicate(observation)
		if predicate == "" || handledCandidate(dataset.Claims, observation) || !usesCurrentEvidence(observation, currentEvidence) {
			continue
		}
		inputs[observation.ID] = observation
	}
	result := make([]Observation, 0, len(inputs))
	for _, observation := range inputs {
		result = append(result, observation)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result
}

func candidatePredicate(observation Observation) string {
	if observation.Kind != "attribute-candidate" && observation.Kind != "relationship-candidate" {
		return ""
	}
	payload, ok := observation.Payload.(map[string]any)
	if !ok {
		return ""
	}
	predicate, _ := payload["predicate"].(string)
	return predicate
}

func handledCandidate(claims []Claim, observation Observation) bool {
	predicate := candidatePredicate(observation)
	for _, claim := range claims {
		if claim.Predicate != predicate ||
			(observation.Kind == "relationship-candidate" && claim.ObjectEntityID == nil) ||
			(observation.Kind == "attribute-candidate" && claim.ObjectEntityID != nil) {
			continue
		}
		for _, supportingID := range claim.SupportingObservationIDs {
			if supportingID == observation.ID {
				return true
			}
		}
	}
	return false
}

func currentExtractionEvidence(dataset Dataset) map[string]bool {
	evidenceByID := map[string]Evidence{}
	for _, evidence := range dataset.Evidence {
		evidenceByID[evidence.ID] = evidence
	}
	type generation struct {
		createdAt int64
		evidence  map[string]bool
	}
	latest := map[string]generation{}
	for _, observation := range dataset.Observations {
		if observation.Kind != ExtractionCompleteKind || observation.Producer.ProcessorID != ExtractionProcessorID {
			continue
		}
		for _, evidenceID := range observation.EvidenceIDs {
			evidence, ok := evidenceByID[evidenceID]
			if !ok {
				continue
			}
			prior := latest[evidence.BronzeSourceID]
			if observation.CreatedAtMillis >= prior.createdAt {
				latest[evidence.BronzeSourceID] = generation{observation.CreatedAtMillis, map[string]bool{evidenceID: true}}
			}
		}
	}
	current := map[string]bool{}
	for _, generation := range latest {
		for evidenceID := range generation.evidence {
			current[evidenceID] = true
		}
	}
	return current
}

func usesCurrentEvidence(observation Observation, current map[string]bool) bool {
	for _, evidenceID := range observation.EvidenceIDs {
		if current[evidenceID] {
			return true
		}
	}
	return false
}

func strengthenClaims(dataset Dataset, now int64) ([]Claim, error) {
	type group struct {
		representative Claim
		observations   map[string]bool
	}
	groups := map[string]*group{}
	for _, claim := range dataset.Claims {
		if claim.State != "active" {
			continue
		}
		identity, err := marshalCanonical(map[string]any{
			"objectEntityId":  claim.ObjectEntityID,
			"predicate":       claim.Predicate,
			"subjectEntityId": claim.SubjectEntityID,
			"value":           claim.Value,
		})
		if err != nil {
			return nil, err
		}
		key := string(identity)
		if groups[key] == nil {
			groups[key] = &group{representative: claim, observations: map[string]bool{}}
		}
		for _, observationID := range claim.SupportingObservationIDs {
			groups[key].observations[observationID] = true
		}
	}
	evidenceByID := map[string]Evidence{}
	for _, evidence := range dataset.Evidence {
		evidenceByID[evidence.ID] = evidence
	}
	observationsByID := map[string]Observation{}
	for _, observation := range dataset.Observations {
		observationsByID[observation.ID] = observation
	}
	producer := Producer{ProcessorID: ResolutionProcessorID, ProcessorVersion: ResolutionVersion}
	var strengthened []Claim
	for _, group := range groups {
		sourceIDs := map[string]bool{}
		observationIDs := make([]string, 0, len(group.observations))
		for observationID := range group.observations {
			observationIDs = append(observationIDs, observationID)
			for _, evidenceID := range observationsByID[observationID].EvidenceIDs {
				if evidence, ok := evidenceByID[evidenceID]; ok {
					sourceIDs[evidence.BronzeSourceID] = true
				}
			}
		}
		if len(sourceIDs) < 2 {
			continue
		}
		claim, err := NewClaim(
			group.representative.SubjectEntityID,
			group.representative.Predicate,
			group.representative.ObjectEntityID,
			group.representative.Value,
			observationIDs,
			nil,
			producer,
			now,
		)
		if err != nil {
			return nil, err
		}
		strengthened = append(strengthened, claim)
	}
	return strengthened, nil
}
