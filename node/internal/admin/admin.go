package admin

import (
	_ "embed"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	localai "source.local/node/internal/ai"
	"source.local/node/internal/apperror"
	"source.local/node/internal/config"
	"source.local/node/internal/database"
	"source.local/node/internal/pairing"
	"source.local/node/internal/ratelimit"
	"source.local/node/internal/security"
)

const cookieName = "source_admin_session"

//go:embed admin.html
var adminHTML []byte

type session struct {
	csrf    string
	expires time.Time
}

type sessions struct {
	mu    sync.Mutex
	now   func() time.Time
	ttl   time.Duration
	items map[string]session
}

func (s *sessions) create() (string, session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	token, err := security.GenerateToken()
	if err != nil {
		return "", session{}, err
	}
	csrf, err := security.GenerateToken()
	if err != nil {
		return "", session{}, err
	}
	value := session{csrf: csrf, expires: s.now().Add(s.ttl)}
	s.items[security.TokenHash(token)] = value
	return token, value, nil
}

func (s *sessions) find(token string) (session, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := security.TokenHash(token)
	value, ok := s.items[key]
	if !ok || !s.now().Before(value.expires) {
		delete(s.items, key)
		return session{}, false
	}
	return value, true
}

func (s *sessions) delete(token string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.items, security.TokenHash(token))
}

type Handler struct {
	db       *database.DB
	cfg      config.Config
	ai       localai.Backend
	pairing  *pairing.Service
	sessions *sessions
	login    *ratelimit.Limiter
	logger   *log.Logger
	mux      *http.ServeMux
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

func New(db *database.DB, cfg config.Config, ai localai.Backend, p *pairing.Service, logger *log.Logger) http.Handler {
	h := &Handler{
		db: db, cfg: cfg, ai: ai, pairing: p,
		sessions: &sessions{now: cfg.Now, ttl: cfg.AdminSessionTTL, items: map[string]session{}},
		login:    ratelimit.New(5, 15*time.Minute, cfg.Now),
		logger:   logger,
		mux:      http.NewServeMux(),
	}
	h.registerRoutes()
	return h
}

func (h *Handler) registerRoutes() {
	h.mux.HandleFunc("/admin/api/state", h.method(http.MethodGet, h.state))
	h.mux.HandleFunc("/admin/api/initialize", h.method(http.MethodPost, h.initialize))
	h.mux.HandleFunc("/admin/api/login", h.method(http.MethodPost, h.loginAdmin))
	h.mux.HandleFunc("/admin/api/logout", h.method(http.MethodPost, h.logout))
	h.mux.HandleFunc("/admin/api/dashboard", h.method(http.MethodGet, h.dashboardResponse))
	h.mux.HandleFunc("/admin/api/pairing-invitations/active", h.method(http.MethodGet, h.activeInvitation))
	h.mux.HandleFunc("/admin/api/pairing-invitations", h.method(http.MethodPost, h.createPairingInvitation))
	h.mux.HandleFunc("/admin/api/pairing-invitations/{id}/qr.svg", h.method(http.MethodGet, h.pairingInvitationQR))
	h.mux.HandleFunc("/admin/api/pairing-invitations/{id}", h.method(http.MethodDelete, h.cancelPairingInvitation))
	h.mux.HandleFunc("/admin/api/users/{id}/recovery-invitations", h.method(http.MethodPost, h.createRecoveryInvitation))
	h.mux.HandleFunc("/admin/api/users/{id}", h.method(http.MethodDelete, h.deleteUser))
	h.mux.HandleFunc("/", h.root)
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
			h.notFound(w)
			return
		}
		next(w, r)
	}
}

func (h *Handler) root(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || r.URL.Path != "/" {
		h.notFound(w)
		return
	}
	secureHeaders(w)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(adminHTML)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(adminHTML)
}

func (h *Handler) notFound(w http.ResponseWriter) {
	h.fail(w, apperror.New(404, "not_found", "Endpoint does not exist."))
}

func (h *Handler) mutating(r *http.Request) error {
	if err := sameOrigin(r); err != nil {
		return err
	}
	_, err := h.authenticated(r, true)
	return err
}

func (h *Handler) authenticatedValue(r *http.Request) (session, bool) {
	return h.sessions.find(cookieValue(r))
}

func (h *Handler) authenticated(r *http.Request, csrf bool) (session, error) {
	value, ok := h.authenticatedValue(r)
	if !ok {
		return session{}, apperror.New(401, "admin_authentication_required", "Admin authentication is required.")
	}
	if csrf && r.Header.Get("X-Source-Csrf") != value.csrf {
		return session{}, apperror.New(403, "csrf_failed", "The request could not be verified.")
	}
	return value, nil
}

func (h *Handler) fail(w http.ResponseWriter, err error) int {
	status, code, message := apperror.Details(err)
	if status >= 500 {
		h.logger.Printf("admin request failed: %v", err)
		message = "Source Node could not complete the request."
	}
	adminJSON(w, status, map[string]any{"error": map[string]any{"code": code, "message": message}})
	return status
}

func adminJSON(w http.ResponseWriter, status int, value any) {
	payload, _ := json.Marshal(value)
	secureHeaders(w)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
	w.WriteHeader(status)
	_, _ = w.Write(payload)
}

func secureHeaders(w http.ResponseWriter) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
}

func readJSON(r *http.Request, destination any) error {
	if !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/json") {
		return apperror.New(415, "unsupported_media_type", "Content-Type must be application/json.")
	}
	body, err := ioReadLimited(r, 32*1024)
	if err != nil {
		return err
	}
	if json.Unmarshal(body, destination) != nil {
		return apperror.New(400, "invalid_json", "Request contains invalid JSON.")
	}
	return nil
}

func ioReadLimited(r *http.Request, max int64) ([]byte, error) {
	body, err := io.ReadAll(io.LimitReader(r.Body, max+1))
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > max {
		return nil, apperror.New(413, "request_too_large", "Request is too large.")
	}
	return body, nil
}

func sameOrigin(r *http.Request) error {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return nil
	}
	parsed, err := url.Parse(origin)
	if err != nil || parsed.Host != r.Host || parsed.Scheme != "http" {
		return apperror.New(403, "invalid_origin", "Request origin is not allowed.")
	}
	return nil
}

func cookieValue(r *http.Request) string {
	cookie, err := r.Cookie(cookieName)
	if err != nil {
		return ""
	}
	return cookie.Value
}

func remoteAddress(r *http.Request) string {
	host := r.RemoteAddr
	if index := strings.LastIndex(host, ":"); index >= 0 {
		return host[:index]
	}
	return host
}

func validPathID(id string) bool {
	return len(id) == 36 && !strings.Contains(id, "/")
}
