package localai

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"source.local/node/internal/config"
)

type Message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}
type Event struct {
	Type, Text, FinishReason                  string
	InputTokens, OutputTokens, ReasoningBytes int
}
type Backend interface {
	Status(context.Context) bool
	Capabilities() map[string]any
	StreamChat(context.Context, []Message, func(Event) error) error
}
type Client struct {
	url, model          string
	parameterCount      int64
	maximumOutputTokens int
	timeout             time.Duration
	http                *http.Client
}

func New(cfg config.Config) *Client {
	return &Client{url: strings.TrimRight(cfg.AIBackendURL, "/"), model: cfg.AIModel, parameterCount: cfg.AIParameterCount, maximumOutputTokens: cfg.AIMaximumOutputTokens, timeout: cfg.AITimeout, http: &http.Client{}}
}
func (c *Client) Status(ctx context.Context) bool {
	ctx, cancel := context.WithTimeout(ctx, min(c.timeout, 1500*time.Millisecond))
	defer cancel()
	req, e := http.NewRequestWithContext(ctx, http.MethodGet, c.url+"/health", nil)
	if e != nil {
		return false
	}
	res, e := c.http.Do(req)
	if e != nil {
		return false
	}
	defer res.Body.Close()
	return res.StatusCode >= 200 && res.StatusCode < 300
}
func (c *Client) Capabilities() map[string]any {
	return map[string]any{"contractVersion": 1, "modelId": c.model, "parameterCount": c.parameterCount, "modalities": []string{"text"}, "streaming": true, "cancellation": true, "maximumContextTokens": 8192, "promptPolicy": "none-v1", "reasoning": "off"}
}
func (c *Client) StreamChat(ctx context.Context, messages []Message, yield func(Event) error) error {
	for _, m := range messages {
		if m.Role != "user" && m.Role != "assistant" {
			return errors.New("Only explicit user and assistant messages are allowed")
		}
	}
	// Treat the configured timeout as an inactivity limit. Slow models can take
	// longer than this to finish a response while still producing a healthy
	// stream, so a single deadline for the whole request would cancel active
	// generations.
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	timeout := time.AfterFunc(c.timeout, cancel)
	defer timeout.Stop()
	body, e := json.Marshal(map[string]any{
		"model": c.model, "messages": messages, "stream": true,
		"stream_options": map[string]any{"include_usage": true},
		"temperature":    0.6, "top_p": 0.9, "max_tokens": c.maximumOutputTokens,
	})
	if e != nil {
		return e
	}
	req, e := http.NewRequestWithContext(ctx, http.MethodPost, c.url+"/v1/chat/completions", bytes.NewReader(body))
	if e != nil {
		return e
	}
	req.Header.Set("Content-Type", "application/json")
	res, e := c.http.Do(req)
	if e != nil {
		return e
	}
	timeout.Reset(c.timeout)
	defer res.Body.Close()
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return fmt.Errorf("Model returned HTTP %d", res.StatusCode)
	}
	scanner := bufio.NewScanner(res.Body)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	visible := 0
	reasoningBytes := 0
	finish := "stop"
	inputTokens := 0
	outputTokens := 0
	for scanner.Scan() {
		timeout.Reset(c.timeout)
		line := scanner.Text()
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		data := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
		if data == "" || data == "[DONE]" {
			continue
		}
		var payload struct {
			Usage struct {
				PromptTokens     int `json:"prompt_tokens"`
				CompletionTokens int `json:"completion_tokens"`
			} `json:"usage"`
			Choices []struct {
				Delta struct {
					Content          any `json:"content"`
					ReasoningContent any `json:"reasoning_content"`
				} `json:"delta"`
				FinishReason any `json:"finish_reason"`
			} `json:"choices"`
		}
		if e = json.Unmarshal([]byte(data), &payload); e != nil {
			return e
		}
		if payload.Usage.PromptTokens > 0 {
			inputTokens = payload.Usage.PromptTokens
		}
		if payload.Usage.CompletionTokens > 0 {
			outputTokens = payload.Usage.CompletionTokens
		}
		if len(payload.Choices) == 0 {
			continue
		}
		choice := payload.Choices[0]
		if reasoning, ok := choice.Delta.ReasoningContent.(string); ok {
			reasoningBytes += len(reasoning)
		}
		if text, ok := choice.Delta.Content.(string); ok && text != "" {
			visible += len(text)
			if e = yield(Event{Type: "delta", Text: text}); e != nil {
				return e
			}
		}
		if reason, ok := choice.FinishReason.(string); ok {
			finish = reason
		}
	}
	if e = scanner.Err(); e != nil {
		return e
	}
	if visible == 0 {
		return errors.New("Model returned an empty response")
	}
	return yield(Event{
		Type: "completed", FinishReason: finish, InputTokens: inputTokens,
		OutputTokens: outputTokens, ReasoningBytes: reasoningBytes,
	})
}
