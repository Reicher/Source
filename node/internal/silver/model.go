package silver

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"
	"source.local/node/internal/security"
)

const (
	DatasetFormat           = "source-silver"
	DatasetFormatVersion    = 1
	Collection              = "silver-datasets"
	ExtractionProcessorID   = "source.node.silver-extraction"
	ExtractionVersion       = "3"
	ResolutionProcessorID   = "source.node.silver-resolution"
	ResolutionVersion       = "2"
	ProfileProcessorID      = "source.node.profile-identity"
	ProfileProcessorVersion = "1"
	ExtractionCompleteKind  = "knowledge-extraction-complete"
)

type Producer struct {
	ProcessorID      string  `json:"processorId"`
	ProcessorVersion string  `json:"processorVersion"`
	ModelID          *string `json:"modelId"`
	ModelRevision    *string `json:"modelRevision"`
}

type Evidence struct {
	ID                  string `json:"id"`
	BronzeSourceID      string `json:"bronzeSourceId"`
	BronzeContentSHA256 string `json:"bronzeContentSha256"`
	Selector            any    `json:"selector"`
	Excerpt             string `json:"excerpt,omitempty"`
}

type Observation struct {
	ID              string   `json:"id"`
	Kind            string   `json:"kind"`
	Payload         any      `json:"payload"`
	EvidenceIDs     []string `json:"evidenceIds"`
	Confidence      *float64 `json:"confidence"`
	Producer        Producer `json:"producer"`
	CreatedAtMillis int64    `json:"createdAtMillis"`
}

type Entity struct {
	ID string `json:"id"`
}

type Claim struct {
	ID                       string   `json:"id"`
	SubjectEntityID          string   `json:"subjectEntityId"`
	Predicate                string   `json:"predicate"`
	ObjectEntityID           *string  `json:"objectEntityId"`
	Value                    any      `json:"value"`
	SupportingObservationIDs []string `json:"supportingObservationIds"`
	Confidence               *float64 `json:"confidence"`
	Producer                 Producer `json:"producer"`
	State                    string   `json:"state"`
	CreatedAtMillis          int64    `json:"createdAtMillis"`
}

type RemovedSource struct {
	BronzeSourceID string `json:"bronzeSourceId"`
	RemovedAt      int64  `json:"removedAtMillis"`
}

type Dataset struct {
	Evidence         []Evidence
	Observations     []Observation
	Entities         []Entity
	Claims           []Claim
	ModifiedAtMillis int64
	RemovedSources   map[string]int64
}

func EmptyDataset() Dataset { return Dataset{RemovedSources: map[string]int64{}} }

func EncodeDataset(dataset Dataset) ([]byte, error) {
	if dataset.Evidence == nil {
		dataset.Evidence = []Evidence{}
	}
	if dataset.Observations == nil {
		dataset.Observations = []Observation{}
	}
	if dataset.Entities == nil {
		dataset.Entities = []Entity{}
	}
	if dataset.Claims == nil {
		dataset.Claims = []Claim{}
	}
	if dataset.RemovedSources == nil {
		dataset.RemovedSources = map[string]int64{}
	}
	sort.Slice(dataset.Evidence, func(i, j int) bool { return dataset.Evidence[i].ID < dataset.Evidence[j].ID })
	sort.Slice(dataset.Observations, func(i, j int) bool { return dataset.Observations[i].ID < dataset.Observations[j].ID })
	sort.Slice(dataset.Entities, func(i, j int) bool { return dataset.Entities[i].ID < dataset.Entities[j].ID })
	sort.Slice(dataset.Claims, func(i, j int) bool { return dataset.Claims[i].ID < dataset.Claims[j].ID })
	removed := make([]RemovedSource, 0, len(dataset.RemovedSources))
	for sourceID, timestamp := range dataset.RemovedSources {
		removed = append(removed, RemovedSource{sourceID, timestamp})
	}
	sort.Slice(removed, func(i, j int) bool { return removed[i].BronzeSourceID < removed[j].BronzeSourceID })
	return marshalCanonical(map[string]any{
		"version": DatasetFormatVersion, "modifiedAtMillis": dataset.ModifiedAtMillis,
		"evidence": dataset.Evidence, "observations": dataset.Observations,
		"entities": dataset.Entities, "claims": dataset.Claims, "removedSources": removed,
	})
}

func DecodeDataset(raw []byte) (Dataset, error) {
	var wire struct {
		Version          int             `json:"version"`
		ModifiedAtMillis int64           `json:"modifiedAtMillis"`
		Evidence         []Evidence      `json:"evidence"`
		Observations     []Observation   `json:"observations"`
		Entities         []Entity        `json:"entities"`
		Claims           []Claim         `json:"claims"`
		RemovedSources   []RemovedSource `json:"removedSources"`
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if err := decoder.Decode(&wire); err != nil {
		return Dataset{}, err
	}
	if wire.Version != DatasetFormatVersion || wire.ModifiedAtMillis < 0 {
		return Dataset{}, errors.New("unsupported or invalid Silver dataset")
	}
	dataset := Dataset{
		Evidence: wire.Evidence, Observations: wire.Observations, Entities: wire.Entities,
		Claims: wire.Claims, ModifiedAtMillis: wire.ModifiedAtMillis,
		RemovedSources: map[string]int64{},
	}
	for _, removed := range wire.RemovedSources {
		dataset.RemovedSources[removed.BronzeSourceID] = removed.RemovedAt
	}
	return dataset, nil
}

func NewEvidence(sourceID, contentSHA string) (Evidence, error) {
	return newEvidence(sourceID, contentSHA, nil, "")
}

func newEvidence(sourceID, contentSHA string, selector any, excerpt string) (Evidence, error) {
	identity := map[string]any{
		"bronzeContentSha256": contentSHA, "bronzeSourceId": sourceID, "selector": selector,
	}
	id, err := recordID("source-silver-evidence", identity)
	return Evidence{ID: id, BronzeSourceID: sourceID, BronzeContentSHA256: contentSHA, Selector: selector, Excerpt: excerpt}, err
}

func NewObservation(kind string, payload any, evidenceIDs []string, confidence *float64, producer Producer, now int64) (Observation, error) {
	evidenceIDs = sortedUnique(evidenceIDs)
	identity := map[string]any{
		"confidence": confidence, "evidenceIds": evidenceIDs, "kind": kind,
		"payload": payload, "producer": producerIdentity(producer),
	}
	id, err := recordID("source-silver-observation", identity)
	return Observation{ID: id, Kind: kind, Payload: payload, EvidenceIDs: evidenceIDs,
		Confidence: confidence, Producer: producer, CreatedAtMillis: now}, err
}

func NewClaim(subjectID, predicate string, objectID *string, value any, observations []string, confidence *float64, producer Producer, now int64) (Claim, error) {
	if (objectID == nil) == (value == nil) {
		return Claim{}, errors.New("Silver Claim requires exactly one object")
	}
	object := map[string]any{"scalar": value}
	if objectID != nil {
		object = map[string]any{"entityId": *objectID}
	}
	observations = sortedUnique(observations)
	identity := map[string]any{
		"confidence": confidence, "object": object, "predicate": predicate,
		"producer": producerIdentity(producer), "subjectEntityId": subjectID,
		"supportingObservationIds": observations,
	}
	id, err := recordID("source-silver-claim", identity)
	return Claim{ID: id, SubjectEntityID: subjectID, Predicate: predicate,
		ObjectEntityID: objectID, Value: value, SupportingObservationIDs: observations,
		Confidence: confidence, Producer: producer, State: "active", CreatedAtMillis: now}, err
}

func producerIdentity(producer Producer) map[string]any {
	return map[string]any{
		"modelId": producer.ModelID, "modelRevision": producer.ModelRevision,
		"processorId": producer.ProcessorID, "processorVersion": producer.ProcessorVersion,
	}
}

func recordID(prefix string, identity any) (string, error) {
	canonical, err := marshalCanonical(identity)
	if err != nil {
		return "", err
	}
	digest := sha256.New()
	_, _ = digest.Write([]byte(prefix))
	_, _ = digest.Write([]byte{0})
	_, _ = digest.Write(canonical)
	return hex.EncodeToString(digest.Sum(nil)), nil
}

func marshalCanonical(value any) ([]byte, error) {
	normalized, err := normalize(value)
	if err != nil {
		return nil, err
	}
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)
	if err = encoder.Encode(normalized); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buffer.Bytes(), []byte{'\n'}), nil
}

func normalize(value any) (any, error) {
	switch typed := value.(type) {
	case string:
		if !utf8.ValidString(typed) {
			return nil, errors.New("invalid Silver Unicode")
		}
		return norm.NFC.String(typed), nil
	case json.Number:
		parsed, err := typed.Float64()
		if err != nil || math.IsNaN(parsed) || math.IsInf(parsed, 0) {
			return nil, errors.New("invalid Silver number")
		}
		return parsed, nil
	case float64:
		if math.IsNaN(typed) || math.IsInf(typed, 0) {
			return nil, errors.New("invalid Silver number")
		}
		if typed == 0 {
			return float64(0), nil
		}
		return typed, nil
	case []string:
		result := make([]any, len(typed))
		for i := range typed {
			result[i] = norm.NFC.String(typed[i])
		}
		return result, nil
	case []any:
		result := make([]any, len(typed))
		for i := range typed {
			var itemErr error
			result[i], itemErr = normalize(typed[i])
			if itemErr != nil {
				return nil, itemErr
			}
		}
		return result, nil
	case map[string]any:
		result := make(map[string]any, len(typed))
		for key, item := range typed {
			normalizedKey := norm.NFC.String(key)
			if _, exists := result[normalizedKey]; exists {
				return nil, errors.New("duplicate normalized Silver property")
			}
			normalizedItem, itemErr := normalize(item)
			if itemErr != nil {
				return nil, itemErr
			}
			result[normalizedKey] = normalizedItem
		}
		return result, nil
	case *float64:
		if typed == nil {
			return nil, nil
		}
		return normalize(*typed)
	case *string:
		if typed == nil {
			return nil, nil
		}
		return normalize(*typed)
	case nil, bool, int, int64:
		return typed, nil
	default:
		raw, err := json.Marshal(typed)
		if err != nil {
			return nil, err
		}
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.UseNumber()
		var generic any
		if err = decoder.Decode(&generic); err != nil {
			return nil, err
		}
		return normalize(generic)
	}
}

func sortedUnique(values []string) []string {
	seen := map[string]struct{}{}
	for _, value := range values {
		seen[value] = struct{}{}
	}
	result := make([]string, 0, len(seen))
	for value := range seen {
		result = append(result, value)
	}
	sort.Strings(result)
	return result
}

func newEntity() (Entity, error) {
	id, err := security.UUID()
	return Entity{ID: id}, err
}

func validOpenText(value string, maximum int) bool {
	return value != "" && len([]rune(value)) <= maximum && utf8.ValidString(value) &&
		norm.NFC.IsNormalString(value) && !strings.ContainsFunc(value, unicode.IsControl)
}

func validateDataset(dataset Dataset) error {
	evidence := map[string]Evidence{}
	for _, item := range dataset.Evidence {
		evidence[item.ID] = item
	}
	observations := map[string]Observation{}
	for _, item := range dataset.Observations {
		for _, evidenceID := range item.EvidenceIDs {
			if _, ok := evidence[evidenceID]; !ok {
				return fmt.Errorf("observation %s references missing evidence", item.ID)
			}
		}
		observations[item.ID] = item
	}
	entities := map[string]struct{}{}
	for _, item := range dataset.Entities {
		entities[item.ID] = struct{}{}
	}
	for _, item := range dataset.Claims {
		if _, ok := entities[item.SubjectEntityID]; !ok {
			return fmt.Errorf("claim %s references missing subject", item.ID)
		}
		for _, observationID := range item.SupportingObservationIDs {
			if _, ok := observations[observationID]; !ok {
				return fmt.Errorf("claim %s references missing observation", item.ID)
			}
		}
	}
	return nil
}
