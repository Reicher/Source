package localai

import (
	"context"
	"encoding/json"
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
		_, _ = w.Write([]byte("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hidden\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"Hej\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"!\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
	}))
	defer server.Close()
	client := New(config.Config{LlamaURL: server.URL, LlamaModel: "source-model", LlamaMaximumOutputTokens: 2048, LlamaTimeout: time.Second})
	if !client.Status(context.Background()) {
		t.Fatal("health endpoint was not available")
	}
	var events []Event
	e := client.StreamChat(context.Background(), []Message{{Role: "user", Content: "Hej"}}, func(event Event) error { events = append(events, event); return nil })
	if e != nil {
		t.Fatal(e)
	}
	if len(events) != 3 || events[0].Text != "Hej" || events[1].Text != "!" || events[2].Type != "completed" || events[2].FinishReason != "stop" {
		t.Fatalf("unexpected events: %#v", events)
	}
	if _, ok := request["system"]; ok {
		t.Fatal("hidden system prompt was sent")
	}
	if request["stream"] != true {
		t.Fatal("streaming was not requested")
	}
}

func TestLlamaRejectsHiddenSystemMessages(t *testing.T) {
	client := New(config.Config{LlamaURL: "http://127.0.0.1", LlamaTimeout: time.Second})
	e := client.StreamChat(context.Background(), []Message{{Role: "system", Content: "hidden"}}, func(Event) error { return nil })
	if e == nil {
		t.Fatal("system message was accepted")
	}
}
