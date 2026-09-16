package silver

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"

	localai "source.local/node/internal/ai"
)

const (
	maximumChunkBytes       = 6000
	chunkOverlapBytes       = 600
	maximumRefinementChunks = 8
	maximumRefinementBytes  = 19_200
)

type extractedGeneration struct {
	Evidence     []Evidence
	Observations []Observation
}

type candidateEntity struct{ Key, Name, Type string }
type candidateClaim struct {
	SubjectKey, Predicate, ObjectKey string
	Value                            any
	Confidence                       float64
	Excerpt                          string
}

func extract(ctx context.Context, ai localai.Backend, source Source, modelID string, now int64) (extractedGeneration, error) {
	chunks := textChunks(source.Text, maximumChunkBytes, chunkOverlapBytes)
	if len(chunks) == 0 {
		return extractedGeneration{}, errors.New("Bronze text is empty")
	}
	outputs := make([]string, len(chunks))
	for index, chunk := range chunks {
		output, err := extractBatch(ctx, ai, chunk, source.AuthoredBySelf)
		if err != nil {
			return extractedGeneration{}, err
		}
		outputs[index] = output
	}
	return generationFromBatchOutputs(source, modelID, now, chunks, outputs)
}

func extractBatch(ctx context.Context, ai localai.Backend, chunk string, authoredBySelf bool) (string, error) {
	var output strings.Builder
	err := ai.StreamChat(ctx, []localai.Message{{Role: "user", Content: extractionPrompt(chunk, authoredBySelf)}}, localai.ChatOptions{
		Temperature: 0.1,
		TopP:        0.8,
		Reasoning:   true,
		JSONSchema:  extractionJSONSchema(),
	}, func(event localai.Event) error {
		if event.Type == "delta" {
			output.WriteString(event.Text)
		}
		return nil
	})
	if err != nil {
		return "", err
	}
	result := output.String()
	if _, err = parseExtraction(result, chunk); err != nil {
		return "", err
	}
	return result, nil
}

func generationFromBatchOutputs(source Source, modelID string, now int64, chunks, outputs []string) (extractedGeneration, error) {
	if len(chunks) == 0 || len(chunks) != len(outputs) {
		return extractedGeneration{}, errors.New("Silver batch checkpoints are incomplete")
	}
	producer := Producer{ProcessorID: ExtractionProcessorID, ProcessorVersion: ExtractionVersion, ModelID: &modelID}
	evidence, err := NewEvidence(source.ID, source.ContentSHA256)
	if err != nil {
		return extractedGeneration{}, err
	}
	observations := map[string]Observation{}
	for index, chunk := range chunks {
		claims, parseErr := parseExtraction(outputs[index], chunk)
		if parseErr != nil {
			return extractedGeneration{}, parseErr
		}
		for _, claim := range claims {
			entities := claim.entities
			payload := map[string]any{
				"subject":   map[string]any{"name": entities[claim.SubjectKey].Name, "type": entities[claim.SubjectKey].Type},
				"predicate": claim.Predicate,
			}
			kind := "attribute-candidate"
			if claim.ObjectKey != "" {
				kind = "relationship-candidate"
				payload["object"] = map[string]any{"name": entities[claim.ObjectKey].Name, "type": entities[claim.ObjectKey].Type}
			} else {
				payload["value"] = claim.Value
			}
			if claim.Excerpt != "" {
				payload["evidenceExcerpt"] = claim.Excerpt
			}
			if source.AuthoredBySelf {
				payload["authoredBySelf"] = true
			}
			confidence := claim.Confidence
			observation, createErr := NewObservation(kind, payload, []string{evidence.ID}, &confidence, producer, now)
			if createErr != nil {
				return extractedGeneration{}, createErr
			}
			if prior, ok := observations[observation.ID]; !ok || observation.CreatedAtMillis < prior.CreatedAtMillis {
				observations[observation.ID] = observation
			}
		}
	}
	complete, err := NewObservation(ExtractionCompleteKind, map[string]any{
		"authoredBySelf": source.AuthoredBySelf,
	}, []string{evidence.ID}, nil, producer, now)
	if err != nil {
		return extractedGeneration{}, err
	}
	observations[complete.ID] = complete
	result := extractedGeneration{Evidence: []Evidence{evidence}}
	for _, item := range observations {
		result.Observations = append(result.Observations, item)
	}
	sort.Slice(result.Observations, func(i, j int) bool { return result.Observations[i].ID < result.Observations[j].ID })
	return result, nil
}

type parsedClaim struct {
	candidateClaim
	entities map[string]candidateEntity
}

func parseExtraction(raw, bronze string) ([]parsedClaim, error) {
	start, end := strings.IndexByte(raw, '{'), strings.LastIndexByte(raw, '}')
	if start < 0 || end < start {
		return nil, errors.New("Silver extraction was not JSON")
	}
	var response struct {
		Entities []struct{ Key, Name, Type string }
		Claims   []struct {
			SubjectKey, Predicate, ObjectKey string
			Value                            any
			Confidence                       *float64
			EvidenceExcerpt                  string
		}
	}
	decoder := json.NewDecoder(strings.NewReader(raw[start : end+1]))
	decoder.UseNumber()
	if err := decoder.Decode(&response); err != nil {
		return nil, fmt.Errorf("decode Silver extraction: %w", err)
	}
	entities := map[string]candidateEntity{}
	for _, rawEntity := range response.Entities {
		key := truncate(strings.TrimSpace(rawEntity.Key), 40)
		name := cleanInline(rawEntity.Name, 200)
		typeName := normalizeType(rawEntity.Type)
		if key != "" && name != "" {
			entities[key] = candidateEntity{key, name, typeName}
		}
	}
	var result []parsedClaim
	for _, rawClaim := range response.Claims {
		if _, ok := entities[rawClaim.SubjectKey]; !ok {
			continue
		}
		predicate := cleanInline(rawClaim.Predicate, 100)
		if predicate == "" || rawClaim.Confidence == nil || *rawClaim.Confidence < 0 || *rawClaim.Confidence > 1 {
			continue
		}
		objectKey := strings.TrimSpace(rawClaim.ObjectKey)
		if objectKey != "" {
			if _, ok := entities[objectKey]; !ok || rawClaim.Value != nil {
				continue
			}
		} else if text, ok := rawClaim.Value.(string); ok {
			candidate := strings.TrimSpace(text)
			if _, exists := entities[candidate]; exists {
				objectKey = candidate
				rawClaim.Value = nil
			}
		}
		value := scalar(rawClaim.Value)
		if (objectKey == "") == (value == nil) {
			continue
		}
		excerpt := cleanInline(rawClaim.EvidenceExcerpt, 240)
		if excerpt != "" && !strings.Contains(strings.ToLower(bronze), strings.ToLower(excerpt)) {
			excerpt = ""
		}
		result = append(result, parsedClaim{candidateClaim: candidateClaim{
			SubjectKey: rawClaim.SubjectKey, Predicate: predicate, ObjectKey: objectKey,
			Value: value, Confidence: *rawClaim.Confidence, Excerpt: excerpt,
		}, entities: entities})
	}
	return result, nil
}

func scalar(value any) any {
	switch typed := value.(type) {
	case string:
		if cleaned := cleanInline(typed, 500); cleaned != "" {
			return cleaned
		}
	case json.Number:
		if number, err := typed.Float64(); err == nil {
			return number
		}
	case float64, bool:
		return typed
	}
	return nil
}

func textChunks(text string, maximum, overlap int) []string {
	text = strings.TrimSpace(text)
	var chunks []string
	for len(text) > 0 {
		if len([]byte(text)) <= maximum {
			chunks = append(chunks, text)
			break
		}
		end, bytes := 0, 0
		for index, character := range text {
			size := utf8.RuneLen(character)
			if bytes+size > maximum {
				break
			}
			bytes += size
			end = index + size
		}
		preferred := end
		for index := end; index > end/2; index-- {
			if text[index-1] == '\n' || unicode.IsSpace(rune(text[index-1])) {
				preferred = index
				break
			}
		}
		chunk := strings.TrimSpace(text[:preferred])
		if chunk != "" {
			chunks = append(chunks, chunk)
		}
		remainder := strings.TrimSpace(text[preferred:])
		if remainder == "" {
			break
		}
		context := trailingContext(chunk, overlap)
		if context == "" {
			text = remainder
		} else {
			text = context + "\n" + remainder
		}
	}
	return chunks
}

func trailingContext(text string, maximum int) string {
	if maximum <= 0 {
		return ""
	}
	if len([]byte(text)) <= maximum {
		return text
	}
	start := len(text) - maximum
	for start < len(text) && !utf8.RuneStart(text[start]) {
		start++
	}
	for index, character := range text[start:] {
		if unicode.IsSpace(character) {
			return strings.TrimSpace(text[start+index:])
		}
	}
	return strings.TrimSpace(text[start:])
}

func extractionPrompt(chunk string, authoredBySelf bool) string {
	authorContext := "No verified author identity is available. Do not assume first-person references identify the Source profile owner."
	if authoredBySelf {
		authorContext = "The Source profile owner is the verified author. Preserve clear singular first-person references as an entity named I with type person; the resolver will bind that mention to the profile's Self entity."
	}
	return `Extract all explicitly stated factual information from the Bronze text as entity attributes or relationships. Return compact JSON only, with this shape:
{"entities":[{"key":"e1","name":"Robin","type":"person"},{"key":"e2","name":"Source","type":"project"}],"claims":[{"subjectKey":"e1","predicate":"created","objectKey":"e2","confidence":0.95,"evidenceExcerpt":"Robin created Source"},{"subjectKey":"e2","predicate":"status","value":"active","confidence":0.8,"evidenceExcerpt":"Source is active"}]}
Resolve references such as pronouns and possessives when their referent is clear from the provided text. If a reference is ambiguous, do not guess.
` + authorContext + `
Every claim must have exactly one objectKey or scalar value. Do not infer facts that are not stated in or clearly entailed by the text. Types and predicates should be short lowercase labels. If nothing useful exists, return empty arrays.

Bronze text:
` + chunk
}

func extractionJSONSchema() map[string]any {
	entity := map[string]any{
		"type": "object", "additionalProperties": false,
		"required": []string{"key", "name", "type"},
		"properties": map[string]any{
			"key":  map[string]any{"type": "string", "minLength": 1},
			"name": map[string]any{"type": "string", "minLength": 1},
			"type": map[string]any{"type": "string", "minLength": 1},
		},
	}
	sharedClaimProperties := map[string]any{
		"subjectKey":      map[string]any{"type": "string", "minLength": 1},
		"predicate":       map[string]any{"type": "string", "minLength": 1},
		"confidence":      map[string]any{"type": "number", "minimum": 0, "maximum": 1},
		"evidenceExcerpt": map[string]any{"type": "string"},
	}
	claimProperties := func(objectProperty string, objectSchema map[string]any) map[string]any {
		properties := make(map[string]any, len(sharedClaimProperties)+1)
		for key, value := range sharedClaimProperties {
			properties[key] = value
		}
		properties[objectProperty] = objectSchema
		return properties
	}
	claim := map[string]any{
		"oneOf": []any{
			map[string]any{
				"type": "object", "additionalProperties": false,
				"required":   []string{"subjectKey", "predicate", "objectKey", "confidence", "evidenceExcerpt"},
				"properties": claimProperties("objectKey", map[string]any{"type": "string", "minLength": 1}),
			},
			map[string]any{
				"type": "object", "additionalProperties": false,
				"required":   []string{"subjectKey", "predicate", "value", "confidence", "evidenceExcerpt"},
				"properties": claimProperties("value", map[string]any{"type": []string{"string", "number", "boolean"}}),
			},
		},
	}
	return map[string]any{
		"type": "object", "additionalProperties": false,
		"required": []string{"entities", "claims"},
		"properties": map[string]any{
			"entities": map[string]any{"type": "array", "items": entity},
			"claims":   map[string]any{"type": "array", "items": claim},
		},
	}
}

func cleanInline(value string, maximum int) string {
	value = strings.Join(strings.FieldsFunc(value, unicode.IsSpace), " ")
	value = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, value)
	return truncate(strings.TrimSpace(value), maximum)
}

func truncate(value string, maximum int) string {
	runes := []rune(value)
	if len(runes) > maximum {
		runes = runes[:maximum]
	}
	return string(runes)
}

var nonType = regexp.MustCompile(`[^a-z0-9]+`)

func normalizeType(value string) string {
	value = strings.Trim(nonType.ReplaceAllString(strings.ToLower(strings.TrimSpace(value)), "-"), "-")
	value = truncate(value, 64)
	if value == "" || value[0] < 'a' || value[0] > 'z' {
		return "concept"
	}
	return value
}
