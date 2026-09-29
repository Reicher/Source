package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	embeddingDefaultRequestTimeout = 5 * time.Minute
	embeddingMaximumResponse       = 64 * 1024 * 1024
)

// ModelIdentity is persisted with every model-derived representation. A
// revision identifies the exact weights/configuration, not merely a model
// family or a mutable runtime alias.
type ModelIdentity struct {
	ID       string `json:"id"`
	Revision string `json:"revision"`
}

// LLM is the model boundary for generative work. Silver semantic extraction
// currently has a narrower semanticModel processor interface; this transport-
// neutral contract keeps future chat/context work independent of that task.
type LLM interface {
	Identity() ModelIdentity
	Complete(context.Context, LLMRequest) (LLMResponse, error)
}

type LLMRequest struct {
	Messages      []semanticMessage
	MaximumTokens int
	JSON          bool
}

type LLMResponse struct {
	Content string
}

var _ LLM = (*httpSemanticModel)(nil)

func (m *httpSemanticModel) Identity() ModelIdentity {
	return ModelIdentity{ID: m.modelID, Revision: m.revision}
}

// Complete exposes the same local OpenAI-compatible runtime through the
// generic LLM boundary. The existing semantic processor keeps its stricter
// task-specific validation and recovery around the same runtime.
func (m *httpSemanticModel) Complete(ctx context.Context, input LLMRequest) (LLMResponse, error) {
	if len(input.Messages) == 0 {
		return LLMResponse{}, errors.New("LLM request requires messages")
	}
	encodedMessages, err := json.Marshal(input.Messages)
	if err != nil {
		return LLMResponse{}, err
	}
	if len(encodedMessages) > m.maximumInput {
		return LLMResponse{}, fmt.Errorf("LLM input is %d bytes; model limit is %d bytes", len(encodedMessages), m.maximumInput)
	}
	maximumTokens := input.MaximumTokens
	if maximumTokens == 0 {
		maximumTokens = m.maximumOutput
	}
	if maximumTokens < 1 || maximumTokens > m.maximumOutput {
		return LLMResponse{}, errors.New("LLM maximum tokens exceed the configured model limit")
	}
	requestBody := struct {
		Model              string            `json:"model"`
		Messages           []semanticMessage `json:"messages"`
		Temperature        float64           `json:"temperature"`
		MaxTokens          int               `json:"max_tokens"`
		ResponseFormat     map[string]any    `json:"response_format,omitempty"`
		ChatTemplateKwargs map[string]any    `json:"chat_template_kwargs"`
	}{
		Model: m.modelID, Messages: input.Messages, Temperature: 0, MaxTokens: maximumTokens,
		ChatTemplateKwargs: map[string]any{"enable_thinking": false},
	}
	if input.JSON {
		requestBody.ResponseFormat = map[string]any{"type": "json_object"}
	}
	payload, err := json.Marshal(requestBody)
	if err != nil {
		return LLMResponse{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, m.endpoint+"/v1/chat/completions", bytes.NewReader(payload))
	if err != nil {
		return LLMResponse{}, err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := m.client.Do(request)
	if err != nil {
		return LLMResponse{}, fmt.Errorf("LLM inference: %w", err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, semanticMaximumResponse+1))
	if err != nil {
		return LLMResponse{}, err
	}
	if len(body) > semanticMaximumResponse {
		return LLMResponse{}, errors.New("LLM response is too large")
	}
	if response.StatusCode != http.StatusOK {
		return LLMResponse{}, fmt.Errorf("LLM returned HTTP %d: %s", response.StatusCode, truncate(strings.TrimSpace(string(body)), 240))
	}
	var completion struct {
		Choices []struct {
			Message semanticMessage `json:"message"`
		} `json:"choices"`
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	if err := decoder.Decode(&completion); err != nil || requireJSONEOF(decoder) != nil || len(completion.Choices) != 1 {
		return LLMResponse{}, errors.New("invalid LLM completion response")
	}
	return LLMResponse{Content: completion.Choices[0].Message.Content}, nil
}

// Embedder is intentionally small: retrieval owns batching, persistence,
// versioning and rebuilds while the implementation only turns text into
// fixed-width vectors.
type Embedder interface {
	Identity() ModelIdentity
	Dimensions() int
	Embed(context.Context, []string) ([][]float32, error)
}

type httpEmbedder struct {
	endpoint   string
	identity   ModelIdentity
	dimensions int
	client     *http.Client
}

func embedderFromEnvironment() (Embedder, error) {
	endpoint := strings.TrimSpace(os.Getenv("SOURCE_EMBEDDING_URL"))
	if endpoint == "" {
		return nil, nil
	}
	modelID := strings.TrimSpace(os.Getenv("SOURCE_EMBEDDING_MODEL_ID"))
	revision := strings.TrimSpace(os.Getenv("SOURCE_EMBEDDING_MODEL_REVISION"))
	if modelID == "" || revision == "" {
		return nil, errors.New("SOURCE_EMBEDDING_MODEL_ID and SOURCE_EMBEDDING_MODEL_REVISION are required with SOURCE_EMBEDDING_URL")
	}
	dimensions, err := positiveEnvironmentInt("SOURCE_EMBEDDING_DIMENSIONS", 0)
	if err != nil {
		return nil, err
	}
	timeoutMinutes, err := positiveEnvironmentInt(
		"SOURCE_EMBEDDING_REQUEST_TIMEOUT_MINUTES", int(embeddingDefaultRequestTimeout/time.Minute),
	)
	if err != nil {
		return nil, err
	}
	return newHTTPEmbedder(endpoint, modelID, revision, dimensions, time.Duration(timeoutMinutes)*time.Minute)
}

func newHTTPEmbedder(endpoint, modelID, revision string, dimensions int, timeout time.Duration) (Embedder, error) {
	endpoint = strings.TrimRight(strings.TrimSpace(endpoint), "/")
	modelID = strings.TrimSpace(modelID)
	revision = strings.TrimSpace(revision)
	if modelID == "" || revision == "" || dimensions <= 0 {
		return nil, errors.New("embedding model identity and positive dimensions are required")
	}
	if timeout <= 0 {
		return nil, errors.New("embedding request timeout must be positive")
	}
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" ||
		(parsed.Scheme != "http" && parsed.Scheme != "https") {
		return nil, errors.New("invalid embedding model URL")
	}
	host := parsed.Hostname()
	if host != "localhost" {
		ip := net.ParseIP(host)
		if ip == nil || !ip.IsLoopback() {
			return nil, errors.New("embedding model URL must use loopback")
		}
	}
	return &httpEmbedder{
		endpoint: endpoint, identity: ModelIdentity{ID: modelID, Revision: revision}, dimensions: dimensions,
		client: &http.Client{Timeout: timeout},
	}, nil
}

func (e *httpEmbedder) Identity() ModelIdentity { return e.identity }
func (e *httpEmbedder) Dimensions() int         { return e.dimensions }

func (e *httpEmbedder) Embed(ctx context.Context, input []string) ([][]float32, error) {
	if len(input) == 0 {
		return [][]float32{}, nil
	}
	if len(input) > 128 {
		return nil, errors.New("embedding batch is too large")
	}
	for _, value := range input {
		if strings.TrimSpace(value) == "" {
			return nil, errors.New("embedding input must not be empty")
		}
	}
	payload, err := json.Marshal(struct {
		Model          string   `json:"model"`
		Input          []string `json:"input"`
		EncodingFormat string   `json:"encoding_format"`
	}{e.identity.ID, input, "float"})
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, e.endpoint+"/v1/embeddings", bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := e.client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("embedding model inference: %w", err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, embeddingMaximumResponse+1))
	if err != nil {
		return nil, fmt.Errorf("read embedding model response: %w", err)
	}
	if len(body) > embeddingMaximumResponse {
		return nil, errors.New("embedding model response is too large")
	}
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("embedding model returned HTTP %d: %s", response.StatusCode, truncate(strings.TrimSpace(string(body)), 240))
	}
	var result struct {
		Data []struct {
			Index     int       `json:"index"`
			Embedding []float32 `json:"embedding"`
		} `json:"data"`
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	if err := decoder.Decode(&result); err != nil || requireJSONEOF(decoder) != nil || len(result.Data) != len(input) {
		return nil, errors.New("invalid embedding model response")
	}
	sort.Slice(result.Data, func(i, j int) bool { return result.Data[i].Index < result.Data[j].Index })
	vectors := make([][]float32, len(input))
	for index, value := range result.Data {
		if value.Index != index || len(value.Embedding) != e.dimensions {
			return nil, fmt.Errorf("embedding model returned invalid vector %d", index)
		}
		nonzero := false
		for _, component := range value.Embedding {
			if math.IsNaN(float64(component)) || math.IsInf(float64(component), 0) {
				return nil, fmt.Errorf("embedding model returned non-finite vector %d", index)
			}
			nonzero = nonzero || component != 0
		}
		if !nonzero {
			return nil, fmt.Errorf("embedding model returned zero vector %d", index)
		}
		vectors[index] = value.Embedding
	}
	return vectors, nil
}

func embeddingIdentityKey(identity ModelIdentity, dimensions int) string {
	return identity.ID + "\x00" + identity.Revision + "\x00" + strconv.Itoa(dimensions)
}
