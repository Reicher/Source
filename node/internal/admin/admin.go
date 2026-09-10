package admin

import (
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode"

	qrcode "github.com/skip2/go-qrcode"
	localai "source.local/node/internal/ai"
	"source.local/node/internal/apperror"
	"source.local/node/internal/config"
	"source.local/node/internal/database"
	"source.local/node/internal/pairing"
	"source.local/node/internal/ratelimit"
	"source.local/node/internal/security"
)

const cookieName = "source_admin_session"
const version = "0.5.0"

var processStarted = time.Now()

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
	token, e := security.GenerateToken()
	if e != nil {
		return "", session{}, e
	}
	csrf, e := security.GenerateToken()
	if e != nil {
		return "", session{}, e
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
}

func New(db *database.DB, cfg config.Config, ai localai.Backend, p *pairing.Service, logger *log.Logger) http.Handler {
	return &Handler{db: db, cfg: cfg, ai: ai, pairing: p, sessions: &sessions{now: cfg.Now, ttl: cfg.AdminSessionTTL, items: map[string]session{}}, login: ratelimit.New(5, 15*time.Minute, cfg.Now), logger: logger}
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
	if route == "GET /" {
		status = 200
		secureHeaders(w)
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Content-Length", strconv.Itoa(len(adminHTML)))
		w.WriteHeader(200)
		_, _ = w.Write(adminHTML)
		return
	}
	if route == "GET /admin/api/state" {
		initialized := h.db.IsInitialized()
		value := map[string]any{"initialized": initialized, "authenticated": false}
		if initialized {
			if s, ok := h.authenticatedValue(r); ok {
				value["authenticated"] = true
				value["csrfToken"] = s.csrf
			}
		} else {
			value["suggestedNodeName"] = h.cfg.SuggestedNodeName
		}
		status = 200
		adminJSON(w, status, value)
		return
	}
	if route == "POST /admin/api/initialize" {
		if e := sameOrigin(r); e != nil {
			status = h.fail(w, e)
			return
		}
		if h.db.IsInitialized() {
			status = h.fail(w, apperror.New(409, "already_initialized", "Source Node is already initialized."))
			return
		}
		var body struct{ DisplayName, Password, PasswordConfirmation string }
		if e := readJSON(r, &body); e != nil {
			status = h.fail(w, e)
			return
		}
		display, e := displayName(body.DisplayName)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		if body.Password != body.PasswordConfirmation {
			status = h.fail(w, apperror.New(400, "password_mismatch", "The passwords do not match."))
			return
		}
		if e = security.ValidatePassword(body.Password); e != nil {
			status = h.fail(w, apperror.New(400, "invalid_password", "Password must contain 12–256 characters."))
			return
		}
		passwordHash, e := security.HashPassword(body.Password)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		identity, e := security.GenerateNodeIdentity()
		if e != nil {
			status = h.fail(w, e)
			return
		}
		node, e := h.db.InitializeNode(display, identity, passwordHash, h.cfg.Now().UnixMilli())
		if e != nil {
			if errors.Is(e, database.ErrAlreadyInitialized) {
				status = h.fail(w, apperror.New(409, "already_initialized", "Source Node is already initialized."))
				return
			}
			status = h.fail(w, e)
			return
		}
		status = 201
		adminJSON(w, status, map[string]any{"initialized": true, "node": map[string]any{"displayName": node.DisplayName, "nodeId": node.NodeID}})
		return
	}
	if route == "POST /admin/api/login" {
		if e := sameOrigin(r); e != nil {
			status = h.fail(w, e)
			return
		}
		if !h.db.IsInitialized() {
			status = h.fail(w, apperror.New(409, "setup_required", "Source Node must be initialized first."))
			return
		}
		if !h.login.Take(remoteAddress(r)) {
			status = h.fail(w, apperror.New(429, "too_many_attempts", "Too many login attempts. Try again later."))
			return
		}
		var body struct {
			Password string `json:"password"`
		}
		if e := readJSON(r, &body); e != nil {
			status = h.fail(w, e)
			return
		}
		node, e := h.db.GetNodeState(true)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		if !security.VerifyPassword(body.Password, node.AdminPasswordHash) {
			status = h.fail(w, apperror.New(401, "invalid_admin_credentials", "Incorrect admin password."))
			return
		}
		token, s, e := h.sessions.create()
		if e != nil {
			status = h.fail(w, e)
			return
		}
		http.SetCookie(w, &http.Cookie{Name: cookieName, Value: token, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: int(h.cfg.AdminSessionTTL / time.Second)})
		status = 200
		adminJSON(w, status, map[string]any{"authenticated": true, "csrfToken": s.csrf})
		return
	}
	if route == "POST /admin/api/logout" {
		if e := sameOrigin(r); e != nil {
			status = h.fail(w, e)
			return
		}
		_, e := h.authenticated(r, true)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		token := cookieValue(r)
		h.sessions.delete(token)
		http.SetCookie(w, &http.Cookie{Name: cookieName, Value: "", Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: -1})
		status = 204
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(204)
		return
	}
	if route == "GET /admin/api/dashboard" {
		if _, e := h.authenticated(r, false); e != nil {
			status = h.fail(w, e)
			return
		}
		value, e := h.dashboard(r.Context())
		if e != nil {
			status = h.fail(w, e)
			return
		}
		status = 200
		adminJSON(w, status, value)
		return
	}
	if route == "GET /admin/api/pairing-invitations/active" {
		if _, e := h.authenticated(r, false); e != nil {
			status = h.fail(w, e)
			return
		}
		value, e := h.pairing.GetInvitation()
		if e != nil {
			status = h.fail(w, e)
			return
		}
		status = 200
		adminJSON(w, status, map[string]any{"invitation": value})
		return
	}
	if route == "POST /admin/api/pairing-invitations" {
		if e := h.mutating(r); e != nil {
			status = h.fail(w, e)
			return
		}
		var body struct {
			QuotaBytes int64 `json:"quotaBytes"`
		}
		if e := readJSON(r, &body); e != nil {
			status = h.fail(w, e)
			return
		}
		value, e := h.pairing.CreateInvitation(body.QuotaBytes)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		status = 201
		adminJSON(w, status, map[string]any{"invitation": value})
		return
	}
	if id, ok := pathID(r.URL.Path, "/admin/api/users/", "/recovery-invitations"); ok && r.Method == http.MethodPost {
		if e := h.mutating(r); e != nil {
			status = h.fail(w, e)
			return
		}
		value, e := h.pairing.CreateRecoveryInvitation(id)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		status = 201
		adminJSON(w, status, map[string]any{"invitation": value})
		return
	}
	if id, ok := pathID(r.URL.Path, "/admin/api/users/", ""); ok && r.Method == http.MethodDelete {
		if e := h.mutating(r); e != nil {
			status = h.fail(w, e)
			return
		}
		var body struct {
			DisplayName string `json:"displayName"`
		}
		if e := readJSON(r, &body); e != nil {
			status = h.fail(w, e)
			return
		}
		user, e := h.db.FindUser(id)
		if e != nil {
			status = h.fail(w, e)
			return
		}
		if user == nil {
			status = h.fail(w, apperror.New(404, "user_not_found", "The user was not found."))
			return
		}
		if body.DisplayName != user.DisplayName {
			status = h.fail(w, apperror.New(400, "delete_confirmation_failed", "The user name confirmation does not match."))
			return
		}
		h.pairing.CancelUserInvitation(user.ID)
		disabled, e := h.db.DisableUser(user.ID, h.cfg.Now().UnixMilli())
		if e != nil {
			status = h.fail(w, e)
			return
		}
		root := filepath.Clean(h.cfg.StorageRoot)
		userStorage := filepath.Join(root, disabled.StorageNamespace)
		if filepath.Dir(userStorage) != root {
			status = h.fail(w, apperror.New(500, "invalid_storage_path", "User storage path is invalid."))
			return
		}
		if e = os.RemoveAll(userStorage); e != nil {
			status = h.fail(w, e)
			return
		}
		if _, e = h.db.DeleteUser(user.ID); e != nil {
			status = h.fail(w, e)
			return
		}
		status = 204
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(204)
		return
	}
	if id, ok := pathID(r.URL.Path, "/admin/api/pairing-invitations/", ""); ok && r.Method == http.MethodDelete {
		if e := h.mutating(r); e != nil {
			status = h.fail(w, e)
			return
		}
		if _, e := h.pairing.CancelInvitation(id); e != nil {
			status = h.fail(w, e)
			return
		}
		status = 204
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(204)
		return
	}
	if id, ok := pathID(r.URL.Path, "/admin/api/pairing-invitations/", "/qr.svg"); ok && r.Method == http.MethodGet {
		if _, e := h.authenticated(r, false); e != nil {
			status = h.fail(w, e)
			return
		}
		value, e := h.pairing.GetInvitation()
		if e != nil {
			status = h.fail(w, e)
			return
		}
		if value == nil || value["state"] != "active" || value["id"] != id {
			status = h.fail(w, apperror.New(404, "invitation_not_found", "No active invitation was found."))
			return
		}
		svg, e := qrSVG(value["payload"].(string))
		if e != nil {
			status = h.fail(w, e)
			return
		}
		status = 200
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "image/svg+xml; charset=utf-8")
		w.Header().Set("Content-Length", strconv.Itoa(len(svg)))
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.WriteHeader(200)
		_, _ = w.Write([]byte(svg))
		return
	}
	status = h.fail(w, apperror.New(404, "not_found", "Endpoint does not exist."))
}

func (h *Handler) mutating(r *http.Request) error {
	if e := sameOrigin(r); e != nil {
		return e
	}
	_, e := h.authenticated(r, true)
	return e
}
func (h *Handler) authenticatedValue(r *http.Request) (session, bool) {
	return h.sessions.find(cookieValue(r))
}
func (h *Handler) authenticated(r *http.Request, csrf bool) (session, error) {
	s, ok := h.authenticatedValue(r)
	if !ok {
		return session{}, apperror.New(401, "admin_authentication_required", "Admin authentication is required.")
	}
	if csrf && r.Header.Get("X-Source-Csrf") != s.csrf {
		return session{}, apperror.New(403, "csrf_failed", "The request could not be verified.")
	}
	return s, nil
}
func (h *Handler) fail(w http.ResponseWriter, e error) int {
	status, code, message := apperror.Details(e)
	if status >= 500 {
		h.logger.Printf("admin request failed: %v", e)
		message = "Source Node could not complete the request."
	}
	adminJSON(w, status, map[string]any{"error": map[string]any{"code": code, "message": message}})
	return status
}

func (h *Handler) dashboard(ctx context.Context) (map[string]any, error) {
	node, e := h.db.GetNodeState(false)
	if e != nil {
		return nil, e
	}
	users, e := h.db.ListUsers()
	if e != nil {
		return nil, e
	}
	public := make([]map[string]any, 0, len(users))
	clients := 0
	for _, u := range users {
		clients += u.ClientCount
		public = append(public, map[string]any{"id": u.ID, "displayName": u.DisplayName, "quotaBytes": u.QuotaBytes, "createdAt": u.CreatedAt, "disabledAt": u.DisabledAt, "recoveryConfigured": u.RecoveryConfigured, "storageUsedBytes": u.StorageUsedBytes, "clientCount": u.ClientCount})
	}
	disk := diskStatus(h.cfg.StorageRoot)
	return map[string]any{"node": map[string]any{"displayName": node.DisplayName, "nodeId": node.NodeID, "fingerprint": tail(node.NodeID, 12), "version": version, "createdAt": time.UnixMilli(node.CreatedAt).UTC().Format(time.RFC3339Nano)}, "status": map[string]any{"service": "online", "processUptimeSeconds": int(time.Since(processStarted).Seconds()), "systemUptimeSeconds": systemUptime(), "cpu": cpuStatus(), "memory": memoryStatus(), "disk": disk, "temperatureCelsius": temperature(), "ai": map[string]any{"available": h.ai.Status(ctx), "model": h.cfg.LlamaModel}, "lan": map[string]any{"host": h.cfg.Host, "port": h.cfg.Port, "pairingEndpoint": h.cfg.PairingBaseURL}, "users": len(users), "pairedClients": clients}, "users": public}, nil
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
	body, e := ioReadLimited(r, 32*1024)
	if e != nil {
		return e
	}
	if json.Unmarshal(body, destination) != nil {
		return apperror.New(400, "invalid_json", "Request contains invalid JSON.")
	}
	return nil
}
func ioReadLimited(r *http.Request, max int64) ([]byte, error) {
	body, e := io.ReadAll(io.LimitReader(r.Body, max+1))
	if e != nil {
		return nil, e
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
	u, e := url.Parse(origin)
	if e != nil || u.Host != r.Host || u.Scheme != "http" {
		return apperror.New(403, "invalid_origin", "Request origin is not allowed.")
	}
	return nil
}
func displayName(value string) (string, error) {
	v := strings.TrimSpace(value)
	if len(v) < 1 || len(v) > 100 {
		return "", apperror.New(400, "invalid_display_name", "Display name must contain 1–100 printable characters.")
	}
	for _, r := range v {
		if unicode.IsControl(r) {
			return "", apperror.New(400, "invalid_display_name", "Display name must contain 1–100 printable characters.")
		}
	}
	return v, nil
}
func cookieValue(r *http.Request) string {
	c, e := r.Cookie(cookieName)
	if e != nil {
		return ""
	}
	return c.Value
}
func remoteAddress(r *http.Request) string {
	host := r.RemoteAddr
	if i := strings.LastIndex(host, ":"); i >= 0 {
		return host[:i]
	}
	return host
}
func pathID(path, prefix, suffix string) (string, bool) {
	if !strings.HasPrefix(path, prefix) || !strings.HasSuffix(path, suffix) {
		return "", false
	}
	id := strings.TrimSuffix(strings.TrimPrefix(path, prefix), suffix)
	if len(id) != 36 || strings.Contains(id, "/") {
		return "", false
	}
	return id, true
}
func tail(v string, n int) string {
	if len(v) <= n {
		return v
	}
	return v[len(v)-n:]
}
func diskStatus(root string) map[string]any {
	var s syscall.Statfs_t
	if syscall.Statfs(root, &s) != nil {
		return map[string]any{"totalBytes": nil, "usedBytes": nil, "availableBytes": nil}
	}
	total := uint64(s.Blocks) * uint64(s.Bsize)
	free := uint64(s.Bfree) * uint64(s.Bsize)
	available := uint64(s.Bavail) * uint64(s.Bsize)
	return map[string]any{"totalBytes": total, "usedBytes": total - free, "availableBytes": available}
}
func memoryStatus() map[string]any {
	if values := procMemory(); values != nil {
		return values
	}
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return map[string]any{"totalBytes": m.Sys, "usedBytes": m.Alloc, "availableBytes": m.Sys - m.Alloc}
}
func procMemory() map[string]any {
	body, e := os.ReadFile("/proc/meminfo")
	if e != nil {
		return nil
	}
	values := map[string]uint64{}
	for _, line := range strings.Split(string(body), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		value, e := strconv.ParseUint(fields[1], 10, 64)
		if e == nil {
			values[strings.TrimSuffix(fields[0], ":")] = value * 1024
		}
	}
	total, ok := values["MemTotal"]
	if !ok {
		return nil
	}
	available := values["MemAvailable"]
	return map[string]any{"totalBytes": total, "usedBytes": total - min(total, available), "availableBytes": available}
}
func cpuStatus() map[string]any {
	model := runtime.GOARCH
	if body, e := os.ReadFile("/proc/cpuinfo"); e == nil {
		for _, line := range strings.Split(string(body), "\n") {
			key, value, found := strings.Cut(line, ":")
			if found && (strings.TrimSpace(key) == "model name" || strings.TrimSpace(key) == "Hardware") {
				model = strings.TrimSpace(value)
				break
			}
		}
	}
	loads := []float64{}
	if body, e := os.ReadFile("/proc/loadavg"); e == nil {
		fields := strings.Fields(string(body))
		for _, field := range fields[:min(3, len(fields))] {
			if value, e := strconv.ParseFloat(field, 64); e == nil {
				loads = append(loads, value)
			}
		}
	}
	return map[string]any{"model": model, "cores": runtime.NumCPU(), "loadAverage": loads}
}
func systemUptime() int64 {
	if body, e := os.ReadFile("/proc/uptime"); e == nil {
		var seconds float64
		if _, e = fmt.Sscanf(string(body), "%f", &seconds); e == nil {
			return int64(seconds)
		}
	}
	return int64(time.Since(processStarted).Seconds())
}
func temperature() any {
	entries, e := filepath.Glob("/sys/class/thermal/thermal_zone*/temp")
	if e != nil {
		return nil
	}
	for _, p := range entries {
		body, e := os.ReadFile(p)
		if e != nil {
			continue
		}
		value, e := strconv.ParseFloat(strings.TrimSpace(string(body)), 64)
		if e == nil && value > 0 {
			if value > 1000 {
				value /= 1000
			}
			return int64(value + 0.5)
		}
	}
	return nil
}
func qrSVG(payload string) (string, error) {
	code, e := qrcode.New(payload, qrcode.Medium)
	if e != nil {
		return "", e
	}
	bitmap := code.Bitmap()
	scale := 4
	size := len(bitmap) * scale
	var b strings.Builder
	fmt.Fprintf(&b, `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="360" height="360"><rect width="100%%" height="100%%" fill="white"/>`, size, size)
	for y, row := range bitmap {
		for x, dark := range row {
			if dark {
				fmt.Fprintf(&b, `<rect x="%d" y="%d" width="%d" height="%d" fill="black"/>`, x*scale, y*scale, scale, scale)
			}
		}
	}
	b.WriteString(`</svg>`)
	return b.String(), nil
}
