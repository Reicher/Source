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

const maximumChunkBytes = 2400

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
	chunks := textChunks(source.Text, maximumChunkBytes)
	if len(chunks) == 0 {
		return extractedGeneration{}, errors.New("Bronze text is empty")
	}
	producer := Producer{ProcessorID: ExtractionProcessorID, ProcessorVersion: ExtractionVersion, ModelID: &modelID}
	evidence, err := NewEvidence(source.ID, source.ContentSHA256)
	if err != nil {
		return extractedGeneration{}, err
	}
	observations := map[string]Observation{}
	for _, chunk := range chunks {
		var output strings.Builder
		err = ai.StreamChat(ctx, []localai.Message{{Role: "user", Content: extractionPrompt(chunk)}}, func(event localai.Event) error {
			if event.Type == "delta" {
				output.WriteString(event.Text)
			}
			return nil
		})
		if err != nil {
			return extractedGeneration{}, err
		}
		claims, parseErr := parseExtraction(output.String(), chunk)
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
	complete, err := NewObservation(ExtractionCompleteKind, map[string]any{}, []string{evidence.ID}, nil, producer, now)
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

func textChunks(text string, maximum int) []string {
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
		text = strings.TrimSpace(text[preferred:])
	}
	return chunks
}

func extractionPrompt(chunk string) string {
	return `Extract entity mentions and factual relationship or attribute candidates from the Bronze text below. Return compact JSON only, with this shape:
{"entities":[{"key":"e1","name":"Robin","type":"person"},{"key":"e2","name":"Source","type":"project"}],"claims":[{"subjectKey":"e1","predicate":"created","objectKey":"e2","confidence":0.95,"evidenceExcerpt":"Robin created Source"},{"subjectKey":"e2","predicate":"status","value":"active","confidence":0.8,"evidenceExcerpt":"Source is active"}]}
Every claim must have exactly one objectKey or scalar value. Do not infer unstated facts. Types and predicates should be short lowercase labels. If nothing useful exists, return empty arrays.

Bronze text:
` + chunk
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
