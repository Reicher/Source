package localai

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
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
	state := client.State(context.Background())
	if state.Availability != "ready" || state.Capabilities["modelId"] != "source-model" {
		t.Fatalf("unexpected runtime state: %#v", state)
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

func TestLlamaDistinguishesMissingAndFailedModels(t *testing.T) {
	for _, test := range []struct {
		status       int
		availability string
		code         string
	}{
		{http.StatusNotFound, "model_not_installed", "model_not_installed"},
		{http.StatusServiceUnavailable, "model_load_failed", "model_load_failed"},
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(test.status)
		}))
		client := New(config.Config{AIBackendURL: server.URL, AIModel: "source-model", AIParameterCount: 123, AITimeout: time.Second})

		state := client.State(context.Background())

		server.Close()
		if state.Availability != test.availability || state.Failure == nil || state.Failure.Code != test.code {
			t.Fatalf("status %d produced %#v", test.status, state)
		}
	}
}

func TestRuntimeStatesMatchSharedJSONSchema(t *testing.T) {
	raw, err := os.ReadFile("../../../contracts/source-ai.schema.json")
	if err != nil {
		t.Fatal(err)
	}
	var schema struct {
		Defs struct {
			RuntimeState struct {
				Properties struct {
					Availability struct {
						Enum []string `json:"enum"`
					} `json:"availability"`
				} `json:"properties"`
			} `json:"runtimeState"`
			Request struct {
				Required []string `json:"required"`
			} `json:"request"`
		} `json:"$defs"`
	}
	if err = json.Unmarshal(raw, &schema); err != nil {
		t.Fatal(err)
	}
	want := []string{"model_not_installed", "model_present", "model_load_failed", "ready", "inference_failed"}
	if !reflect.DeepEqual(schema.Defs.RuntimeState.Properties.Availability.Enum, want) {
		t.Fatalf("runtime states differ from schema: %#v", schema.Defs.RuntimeState.Properties.Availability.Enum)
	}
	foundTimeout := false
	for _, field := range schema.Defs.Request.Required {
		foundTimeout = foundTimeout || field == "timeoutMillis"
	}
	if !foundTimeout {
		t.Fatal("shared request schema does not require timeoutMillis")
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
