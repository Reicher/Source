package localai

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"source.local/node/internal/config"
)

func TestLlamaStreamsOnlyVisibleContent(t *testing.T) {
	var request map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			w.WriteHeader(200)
			return
		}
		if e := json.NewDecoder(r.Body).Decode(&request); e != nil {
			t.Error(e)
		}
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hidden\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"!\"},\"finish_reason\":\"stop\"}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"))
	}))
	defer server.Close()
	client := New(config.Config{AIBackendURL: server.URL, AIModel: "source-model", AIParameterCount: 123, AIMaximumOutputTokens: 2048, AITimeout: time.Second})
	capabilities := client.Capabilities()
	if capabilities["modelId"] != "source-model" || capabilities["parameterCount"] != int64(123) {
		t.Fatalf("unexpected model metadata: %#v", capabilities)
	}
	if !client.Status(context.Background()) {
		t.Fatal("health endpoint was not available")
	}
	var events []Event
	e := client.StreamChat(context.Background(), []Message{{Role: "user", Content: "Hello"}}, func(event Event) error { events = append(events, event); return nil })
	if e != nil {
		t.Fatal(e)
	}
	if len(events) != 3 || events[0].Text != "Hello" || events[1].Text != "!" || events[2].Type != "completed" || events[2].FinishReason != "stop" || events[2].InputTokens != 12 || events[2].OutputTokens != 3 || events[2].ReasoningBytes != 6 {
		t.Fatalf("unexpected events: %#v", events)
	}
	if _, ok := request["system"]; ok {
		t.Fatal("hidden system prompt was sent")
	}
	if request["stream"] != true {
		t.Fatal("streaming was not requested")
	}
	streamOptions, ok := request["stream_options"].(map[string]any)
	if !ok || streamOptions["include_usage"] != true {
		t.Fatal("streaming token usage was not requested")
	}
}

func TestLlamaTimeoutIsResetWhileStreamIsActive(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		flusher := w.(http.Flusher)
		for _, data := range []string{
			`{"choices":[{"delta":{"content":"Still "}}]}`,
			`{"choices":[{"delta":{"content":"working"},"finish_reason":"stop"}]}`,
			`[DONE]`,
		} {
			_, _ = fmt.Fprintf(w, "data: %s\n\n", data)
			flusher.Flush()
			time.Sleep(60 * time.Millisecond)
		}
	}))
	defer server.Close()

	client := New(config.Config{
		AIBackendURL:          server.URL,
		AIModel:               "source-model",
		AIMaximumOutputTokens: 2048,
		AITimeout:             100 * time.Millisecond,
	})
	var events []Event
	err := client.StreamChat(context.Background(), []Message{{Role: "user", Content: "Hello"}}, func(event Event) error {
		events = append(events, event)
		return nil
	})

	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 3 || events[2].Type != "completed" {
		t.Fatalf("unexpected events: %#v", events)
	}
}

func TestLlamaRejectsHiddenSystemMessages(t *testing.T) {
	client := New(config.Config{AIBackendURL: "http://127.0.0.1", AITimeout: time.Second})
	e := client.StreamChat(context.Background(), []Message{{Role: "system", Content: "hidden"}}, func(Event) error { return nil })
	if e == nil {
		t.Fatal("system message was accepted")
	}
}
