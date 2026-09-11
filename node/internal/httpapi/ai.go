package httpapi

import (
	"encoding/json"
	"net/http"
	"regexp"
	"strings"

	localai "source.local/node/internal/ai"
	"source.local/node/internal/apperror"
	"source.local/node/internal/auth"
)

var uuid = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

type aiRequest struct {
	ContractVersion int    `json:"contractVersion"`
	RunID           string `json:"runId"`
	ConversationID  string `json:"conversationId"`
	Messages        []struct {
		Role    string `json:"role"`
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	} `json:"messages"`
	Workload string `json:"workload"`
}

func (h *Handler) streamAI(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	var body aiRequest
	if err := readJSON(r, 64*1024, &body); err != nil {
		h.fail(w, err)
		return
	}
	messages, err := validateAI(body)
	if err != nil {
		h.fail(w, err)
		return
	}
	limiter := h.chat
	code := "chat_rate_limited"
	if body.Workload == "background" {
		limiter = h.background
		code = "background_ai_rate_limited"
	}
	if !limiter.Take(session.User.ID) {
		h.fail(w, apperror.New(429, code, "Too many AI requests. Wait a moment."))
		return
	}

	w.Header().Set("Content-Type", "application/x-ndjson; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store, no-transform")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	flusher, _ := w.(http.Flusher)
	emit := func(value any) error {
		err := json.NewEncoder(w).Encode(value)
		if flusher != nil {
			flusher.Flush()
		}
		return err
	}
	capabilities := h.ai.Capabilities()
	_ = emit(map[string]any{
		"type": "started", "runId": body.RunID,
		"modelId": capabilities["modelId"], "parameterCount": capabilities["parameterCount"],
	})
	sequence := 0
	err = h.ai.StreamChat(r.Context(), messages, func(event localai.Event) error {
		if event.Type == "delta" {
			defer func() { sequence++ }()
			return emit(map[string]any{"type": "delta", "runId": body.RunID, "sequence": sequence, "text": event.Text})
		}
		if event.Type == "completed" {
			return emit(map[string]any{"type": "completed", "runId": body.RunID, "finishReason": event.FinishReason})
		}
		return nil
	})
	if err != nil && r.Context().Err() == nil {
		h.logger.Printf("streaming local model request failed: %v", err)
		_ = emit(map[string]any{"type": "failed", "runId": body.RunID, "code": "model_unavailable", "retryable": true})
	}
}

func validateAI(body aiRequest) ([]localai.Message, error) {
	if body.ContractVersion != 1 || !uuid.MatchString(body.RunID) || len(body.ConversationID) < 1 || len(body.ConversationID) > 200 || body.Messages == nil {
		return nil, apperror.New(400, "invalid_ai_request", "The AI request is invalid.")
	}
	if len(body.Messages) < 1 || len(body.Messages) > 20 {
		return nil, apperror.New(400, "invalid_messages", "Send between 1 and 20 messages.")
	}
	if body.Workload != "" && body.Workload != "interactive" && body.Workload != "background" {
		return nil, apperror.New(400, "invalid_ai_request", "The AI workload is invalid.")
	}
	messages := make([]localai.Message, 0, len(body.Messages))
	total := 0
	for _, message := range body.Messages {
		if message.Role != "user" && message.Role != "assistant" {
			return nil, apperror.New(400, "invalid_message_role", "Only user and assistant roles are allowed.")
		}
		if len(message.Content) != 1 || message.Content[0].Type != "text" {
			return nil, apperror.New(400, "invalid_message", "Every message must contain text.")
		}
		text := strings.TrimSpace(message.Content[0].Text)
		if len(text) < 1 || len(text) > 4000 {
			return nil, apperror.New(400, "invalid_message", "Every message must contain 1–4000 characters.")
		}
		total += len(text)
		messages = append(messages, localai.Message{Role: message.Role, Content: text})
	}
	if total > 16000 || messages[len(messages)-1].Role != "user" {
		return nil, apperror.New(400, "invalid_messages", "The chat history is too large or does not end with a question.")
	}
	return messages, nil
}
