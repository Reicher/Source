package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	localai "source.local/node/internal/ai"
	"source.local/node/internal/apperror"
	"source.local/node/internal/auth"
	"source.local/node/internal/config"
	"source.local/node/internal/database"
	"source.local/node/internal/pairing"
	"source.local/node/internal/ratelimit"
	"source.local/node/internal/security"
	"source.local/node/internal/storage"
)

var listPath = regexp.MustCompile(`^/api/v1/storage/([a-z][a-z0-9-]{1,31})/snapshots$`)
var latestPath = regexp.MustCompile(`^/api/v1/storage/([a-z][a-z0-9-]{1,31})/snapshots/latest$`)
var itemPath = regexp.MustCompile(`(?i)^/api/v1/storage/([a-z][a-z0-9-]{1,31})/snapshots/([0-9a-f-]{36})$`)
var uuid = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

type Handler struct {
	db      *database.DB
	cfg     config.Config
	ai      localai.Backend
	pairing *pairing.Service
	auth    *auth.Service
	storage *storage.Storage
	chat    *ratelimit.Limiter
	logger  *log.Logger
}

func New(db *database.DB, cfg config.Config, ai localai.Backend, p *pairing.Service, logger *log.Logger) http.Handler {
	return &Handler{db: db, cfg: cfg, ai: ai, pairing: p, auth: auth.New(db, cfg.Now), storage: storage.New(db, cfg.StorageRoot, cfg.SnapshotRetention, cfg.Now), chat: ratelimit.New(10, time.Minute, cfg.Now), logger: logger}
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	started := h.cfg.Now()
	status := 500
	defer func() {
		h.logger.Printf("%s %s %d %dms", r.Method, r.URL.Path, status, h.cfg.Now().Sub(started).Milliseconds())
	}()
	if r.URL.RawQuery != "" {
		status = h.fail(w, apperror.New(400, "query_not_supported", "Query parameters are not supported."))
		return
	}
	route := r.Method + " " + r.URL.Path
	if route == "GET /healthz" {
		status = 200
		writeJSON(w, status, map[string]any{"status": "ok"})
		return
	}
	if route == "GET /api/v1/status" {
		status = 200
		writeJSON(w, status, map[string]any{"service": "source-node", "apiVersion": 1, "llmAvailable": h.ai.Status(r.Context()), "ai": h.ai.Capabilities()})
		return
	}
	if route == "POST /api/v1/pairing/start" {
		var body pairing.StartRequest
		if e := readJSON(w, r, 64*1024, &body); e != nil {
			status = h.fail(w, e)
			return
		}
		value, e := h.pairing.Start(body)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		status = 200
		writeJSON(w, status, value)
		return
	}
	if route == "POST /api/v1/pairing/complete" {
		var body pairing.CompleteRequest
		if e := readJSON(w, r, 64*1024, &body); e != nil {
			status = h.fail(w, e)
			return
		}
		value, e := h.pairing.Complete(body)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		status = 201
		writeJSON(w, status, value)
		return
	}
	session, e := h.auth.Authenticate(bearer(r))
	if e != nil {
		status = h.fail(w, e)
		return
	}
	if session == nil {
		status = h.fail(w, apperror.New(401, "authentication_required", "Valid authentication is required."))
		return
	}
	if route == "GET /api/v1/me" {
		status = 200
		writeJSON(w, status, map[string]any{"user": session.User, "clientId": session.ClientID})
		return
	}
	if route == "POST /api/v1/identity/challenge" {
		var body struct {
			Protocol int    `json:"protocol"`
			Nonce    string `json:"nonce"`
		}
		if e = readJSON(w, r, 64*1024, &body); e != nil {
			status = h.fail(w, e)
			return
		}
		value, e := auth.ProveNodeIdentity(h.db, session, body.Protocol, body.Nonce)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		status = 200
		writeJSON(w, status, value)
		return
	}
	if route == "POST /api/v1/recovery/setup" {
		var body struct {
			RecoveryKey      string `json:"recoveryKey"`
			RecoveryEnvelope string `json:"recoveryEnvelope"`
		}
		if e = readJSON(w, r, 64*1024, &body); e != nil {
			status = h.fail(w, e)
			return
		}
		if !validExact(body.RecoveryKey, 43) || !validExact(body.RecoveryEnvelope, 80) {
			status = h.fail(w, apperror.New(400, "invalid_recovery_material", "Recovery material is invalid."))
			return
		}
		ok, e := h.db.ConfigureRecovery(session.User.ID, security.TokenHash(body.RecoveryKey), body.RecoveryEnvelope)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		if !ok {
			status = h.fail(w, apperror.New(409, "recovery_already_configured", "The recovery key is already configured."))
			return
		}
		status = 201
		writeJSON(w, status, map[string]any{"recoveryConfigured": true})
		return
	}
	if route == "POST /api/v1/ai/stream" {
		if !h.chat.Take(session.User.ID) {
			status = h.fail(w, apperror.New(429, "chat_rate_limited", "Too many AI requests. Wait a moment."))
			return
		}
		var body aiRequest
		if e = readJSON(w, r, 64*1024, &body); e != nil {
			status = h.fail(w, e)
			return
		}
		messages, e := validateAI(body)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		status = 200
		w.Header().Set("Content-Type", "application/x-ndjson; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store, no-transform")
		w.Header().Set("X-Accel-Buffering", "no")
		w.WriteHeader(200)
		flusher, _ := w.(http.Flusher)
		emit := func(v any) error {
			e := json.NewEncoder(w).Encode(v)
			if flusher != nil {
				flusher.Flush()
			}
			return e
		}
		_ = emit(map[string]any{"type": "started", "runId": body.RunID})
		sequence := 0
		e = h.ai.StreamChat(r.Context(), messages, func(event localai.Event) error {
			if event.Type == "delta" {
				defer func() { sequence++ }()
				return emit(map[string]any{"type": "delta", "runId": body.RunID, "sequence": sequence, "text": event.Text})
			}
			if event.Type == "completed" {
				return emit(map[string]any{"type": "completed", "runId": body.RunID, "finishReason": event.FinishReason})
			}
			return nil
		})
		if e != nil && r.Context().Err() == nil {
			h.logger.Printf("streaming local model request failed: %v", e)
			_ = emit(map[string]any{"type": "failed", "runId": body.RunID, "code": "model_unavailable", "retryable": true})
		}
		return
	}
	if m := listPath.FindStringSubmatch(r.URL.Path); r.Method == http.MethodGet && m != nil {
		if !h.allowed(m[1]) {
			status = h.fail(w, apperror.New(403, "app_not_allowed", "The app does not have access to storage."))
			return
		}
		items, e := h.storage.List(session.User.ID, m[1])
		if e != nil {
			status = h.fail(w, e)
			return
		}
		if items == nil {
			items = []database.Snapshot{}
		}
		status = 200
		writeJSON(w, status, map[string]any{"snapshots": items})
		return
	}
	if m := latestPath.FindStringSubmatch(r.URL.Path); r.Method == http.MethodGet && m != nil {
		if !h.allowed(m[1]) {
			status = h.fail(w, apperror.New(403, "app_not_allowed", "The app does not have access to storage."))
			return
		}
		value, e := h.storage.Latest(session.User.ID, m[1])
		if e != nil {
			status = h.fail(w, e)
			return
		}
		if value == nil {
			status = h.fail(w, apperror.New(404, "snapshot_not_found", "No backup exists."))
			return
		}
		status = 200
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Length", strconv.FormatInt(value.Metadata.Bytes, 10))
		w.Header().Set("X-Snapshot-Id", value.Metadata.ID)
		w.Header().Set("X-Snapshot-Created-At", time.UnixMilli(value.Metadata.CreatedAt).UTC().Format(time.RFC3339Nano))
		w.Header().Set("X-Content-SHA256", value.Metadata.SHA256)
		w.WriteHeader(200)
		_, _ = w.Write(value.Body)
		return
	}
	if m := itemPath.FindStringSubmatch(r.URL.Path); m != nil && r.Method == http.MethodPut {
		if !h.allowed(m[1]) {
			status = h.fail(w, apperror.New(403, "app_not_allowed", "The app does not have access to storage."))
			return
		}
		if !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/octet-stream") {
			status = h.fail(w, apperror.New(415, "unsupported_media_type", "The snapshot must use application/octet-stream."))
			return
		}
		body, e := readBody(r, h.cfg.MaximumSnapshotBytes)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		if len(body) < 32 {
			status = h.fail(w, apperror.New(400, "invalid_snapshot", "The snapshot is too small."))
			return
		}
		sum := sha256.Sum256(body)
		actual := hex.EncodeToString(sum[:])
		declared := r.Header.Get("X-Content-SHA256")
		if declared != "" && strings.ToLower(declared) != actual {
			status = h.fail(w, apperror.New(400, "snapshot_hash_mismatch", "The snapshot checksum does not match."))
			return
		}
		metadata, e := h.storage.Put(session.User.ID, m[1], m[2], body)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		status = 201
		w.Header().Set("Location", r.URL.Path)
		writeJSON(w, status, map[string]any{"snapshot": metadata})
		return
	}
	if m := itemPath.FindStringSubmatch(r.URL.Path); m != nil && r.Method == http.MethodDelete {
		if !h.allowed(m[1]) {
			status = h.fail(w, apperror.New(403, "app_not_allowed", "The app does not have access to storage."))
			return
		}
		ok, e := h.storage.Delete(session.User.ID, m[1], m[2])
		if e != nil {
			status = h.fail(w, e)
			return
		}
		if !ok {
			status = h.fail(w, apperror.New(404, "snapshot_not_found", "The backup does not exist."))
			return
		}
		status = 204
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(204)
		return
	}
	status = h.fail(w, apperror.New(404, "not_found", "The endpoint does not exist."))
}

func (h *Handler) allowed(app string) bool { _, ok := h.cfg.AllowedStorageApps[app]; return ok }
func (h *Handler) fail(w http.ResponseWriter, e error) int {
	status, code, message := apperror.Details(e)
	if status >= 500 {
		h.logger.Printf("source node request failed: %v", e)
		message = "The server could not complete the request."
	}
	writeJSON(w, status, map[string]any{"error": map[string]any{"code": code, "message": message}})
	return status
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	payload, _ := json.Marshal(value)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
	w.WriteHeader(status)
	_, _ = w.Write(payload)
}
func bearer(r *http.Request) string {
	v := r.Header.Get("Authorization")
	if !strings.HasPrefix(v, "Bearer ") {
		return ""
	}
	v = strings.TrimPrefix(v, "Bearer ")
	if len(v) < 32 || len(v) > 256 {
		return ""
	}
	return v
}
func readJSON(_ http.ResponseWriter, r *http.Request, max int64, destination any) error {
	if !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/json") {
		return apperror.New(415, "unsupported_media_type", "Content-Type must be application/json.")
	}
	body, e := readBody(r, max)
	if e != nil {
		return e
	}
	if e = json.Unmarshal(body, destination); e != nil {
		return apperror.New(400, "invalid_json", "The request contains invalid JSON.")
	}
	return nil
}
func readBody(r *http.Request, max int64) ([]byte, error) {
	body, e := io.ReadAll(io.LimitReader(r.Body, max+1))
	if e != nil {
		return nil, e
	}
	if int64(len(body)) > max {
		return nil, apperror.New(413, "request_too_large", "The request is too large.")
	}
	return body, nil
}

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
}

func validateAI(body aiRequest) ([]localai.Message, error) {
	if body.ContractVersion != 1 || !uuid.MatchString(body.RunID) || len(body.ConversationID) < 1 || len(body.ConversationID) > 200 || body.Messages == nil {
		return nil, apperror.New(400, "invalid_ai_request", "The AI request is invalid.")
	}
	if len(body.Messages) < 1 || len(body.Messages) > 20 {
		return nil, apperror.New(400, "invalid_messages", "Send between 1 and 20 messages.")
	}
	messages := make([]localai.Message, 0, len(body.Messages))
	total := 0
	for _, m := range body.Messages {
		if m.Role != "user" && m.Role != "assistant" {
			return nil, apperror.New(400, "invalid_message_role", "Only user and assistant roles are allowed.")
		}
		if len(m.Content) != 1 || m.Content[0].Type != "text" {
			return nil, apperror.New(400, "invalid_message", "Every message must contain text.")
		}
		text := strings.TrimSpace(m.Content[0].Text)
		if len(text) < 1 || len(text) > 4000 {
			return nil, apperror.New(400, "invalid_message", "Every message must contain 1–4000 characters.")
		}
		total += len(text)
		messages = append(messages, localai.Message{Role: m.Role, Content: text})
	}
	if total > 16000 || messages[len(messages)-1].Role != "user" {
		return nil, apperror.New(400, "invalid_messages", "The chat history is too large or does not end with a question.")
	}
	return messages, nil
}
func validExact(v string, n int) bool {
	if len(v) != n {
		return false
	}
	for _, c := range v {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}
