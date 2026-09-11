package httpapi

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
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
	"source.local/node/internal/storage"
)

type Handler struct {
	db         *database.DB
	cfg        config.Config
	ai         localai.Backend
	pairing    *pairing.Service
	auth       *auth.Service
	storage    *storage.Storage
	chat       *ratelimit.Limiter
	background *ratelimit.Limiter
	logger     *log.Logger
	mux        *http.ServeMux
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *statusWriter) Write(body []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(body)
}

func (w *statusWriter) Flush() {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	if flusher, ok := w.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

func New(db *database.DB, cfg config.Config, ai localai.Backend, p *pairing.Service, logger *log.Logger) http.Handler {
	h := &Handler{
		db: db, cfg: cfg, ai: ai, pairing: p,
		auth:       auth.New(db, cfg.Now),
		storage:    storage.New(db, cfg.StorageRoot, cfg.SnapshotRetention, cfg.Now),
		chat:       ratelimit.New(10, time.Minute, cfg.Now),
		background: ratelimit.New(10_000, time.Hour, cfg.Now),
		logger:     logger,
		mux:        http.NewServeMux(),
	}
	h.registerRoutes()
	return h
}

func (h *Handler) registerRoutes() {
	h.mux.HandleFunc("/healthz", h.method(http.MethodGet, h.health))
	h.mux.HandleFunc("/api/v1/status", h.method(http.MethodGet, h.status))
	h.mux.HandleFunc("/api/v1/pairing/start", h.method(http.MethodPost, h.startPairing))
	h.mux.HandleFunc("/api/v1/pairing/complete", h.method(http.MethodPost, h.completePairing))
	h.mux.HandleFunc("/api/v1/me", h.method(http.MethodGet, h.requireClient(h.me)))
	h.mux.HandleFunc("/api/v1/identity/challenge", h.method(http.MethodPost, h.requireClient(h.identityChallenge)))
	h.mux.HandleFunc("/api/v1/recovery/setup", h.method(http.MethodPost, h.requireClient(h.setupRecovery)))
	h.mux.HandleFunc("/api/v1/ai/stream", h.method(http.MethodPost, h.requireClient(h.streamAI)))
	h.mux.HandleFunc("/api/v1/storage/{app}/snapshots", h.method(http.MethodGet, h.requireClient(h.listSnapshots)))
	h.mux.HandleFunc("/api/v1/storage/{app}/snapshots/latest", h.method(http.MethodGet, h.requireClient(h.latestSnapshot)))
	h.mux.HandleFunc("/api/v1/storage/{app}/snapshots/{snapshot}", h.storageItem)
	h.mux.HandleFunc("/api/v1/library/items/{item}", h.libraryItem)
	h.mux.HandleFunc("/", h.unmatched)
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	started := h.cfg.Now()
	tracked := &statusWriter{ResponseWriter: w}
	defer func() {
		status := tracked.status
		if status == 0 {
			status = http.StatusInternalServerError
		}
		h.logger.Printf("%s %s %d %dms", r.Method, r.URL.Path, status, h.cfg.Now().Sub(started).Milliseconds())
	}()

	if r.URL.RawQuery != "" {
		h.fail(tracked, apperror.New(400, "query_not_supported", "Query parameters are not supported."))
		return
	}
	h.mux.ServeHTTP(tracked, r)
}

func (h *Handler) method(method string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != method {
			h.unmatched(w, r)
			return
		}
		next(w, r)
	}
}

type clientHandler func(http.ResponseWriter, *http.Request, *auth.Session)

func (h *Handler) requireClient(next clientHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, err := h.auth.Authenticate(bearer(r))
		if err != nil {
			h.fail(w, err)
			return
		}
		if session == nil {
			h.fail(w, apperror.New(401, "authentication_required", "Valid authentication is required."))
			return
		}
		next(w, r, session)
	}
}

func (h *Handler) unmatched(w http.ResponseWriter, r *http.Request) {
	session, err := h.auth.Authenticate(bearer(r))
	if err != nil {
		h.fail(w, err)
		return
	}
	if session == nil {
		h.fail(w, apperror.New(401, "authentication_required", "Valid authentication is required."))
		return
	}
	h.notFound(w, r)
}

func (h *Handler) notFound(w http.ResponseWriter, _ *http.Request) {
	h.fail(w, apperror.New(404, "not_found", "The endpoint does not exist."))
}

func (h *Handler) fail(w http.ResponseWriter, err error) int {
	status, code, message := apperror.Details(err)
	if status >= 500 {
		h.logger.Printf("source node request failed: %v", err)
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
	value := r.Header.Get("Authorization")
	if !strings.HasPrefix(value, "Bearer ") {
		return ""
	}
	value = strings.TrimPrefix(value, "Bearer ")
	if len(value) < 32 || len(value) > 256 {
		return ""
	}
	return value
}

func readJSON(r *http.Request, max int64, destination any) error {
	if !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/json") {
		return apperror.New(415, "unsupported_media_type", "Content-Type must be application/json.")
	}
	body, err := readBody(r, max)
	if err != nil {
		return err
	}
	if err = json.Unmarshal(body, destination); err != nil {
		return apperror.New(400, "invalid_json", "The request contains invalid JSON.")
	}
	return nil
}

func readBody(r *http.Request, max int64) ([]byte, error) {
	body, err := io.ReadAll(io.LimitReader(r.Body, max+1))
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > max {
		return nil, apperror.New(413, "request_too_large", "The request is too large.")
	}
	return body, nil
}
