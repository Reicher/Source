package admin

import (
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode"

	"source.local/node/internal/apperror"
	"source.local/node/internal/database"
	"source.local/node/internal/security"
)

func (h *Handler) state(w http.ResponseWriter, r *http.Request) {
	initialized := h.db.IsInitialized()
	value := map[string]any{"initialized": initialized, "authenticated": false}
	if initialized {
		if adminSession, ok := h.authenticatedValue(r); ok {
			value["authenticated"] = true
			value["csrfToken"] = adminSession.csrf
		}
	} else {
		value["suggestedNodeName"] = h.cfg.SuggestedNodeName
	}
	adminJSON(w, http.StatusOK, value)
}

func (h *Handler) initialize(w http.ResponseWriter, r *http.Request) {
	if err := sameOrigin(r); err != nil {
		h.fail(w, err)
		return
	}
	if h.db.IsInitialized() {
		h.fail(w, apperror.New(409, "already_initialized", "Source Node is already initialized."))
		return
	}
	var body struct{ DisplayName, Password, PasswordConfirmation string }
	if err := readJSON(r, &body); err != nil {
		h.fail(w, err)
		return
	}
	display, err := displayName(body.DisplayName)
	if err != nil {
		h.fail(w, err)
		return
	}
	if body.Password != body.PasswordConfirmation {
		h.fail(w, apperror.New(400, "password_mismatch", "The passwords do not match."))
		return
	}
	if err = security.ValidatePassword(body.Password); err != nil {
		h.fail(w, apperror.New(400, "invalid_password", "Password must contain 12–256 characters."))
		return
	}
	passwordHash, err := security.HashPassword(body.Password)
	if err != nil {
		h.fail(w, err)
		return
	}
	identity, err := security.GenerateNodeIdentity()
	if err != nil {
		h.fail(w, err)
		return
	}
	node, err := h.db.InitializeNode(display, identity, passwordHash, h.cfg.Now().UnixMilli())
	if err != nil {
		if errors.Is(err, database.ErrAlreadyInitialized) {
			h.fail(w, apperror.New(409, "already_initialized", "Source Node is already initialized."))
			return
		}
		h.fail(w, err)
		return
	}
	adminJSON(w, http.StatusCreated, map[string]any{
		"initialized": true,
		"node":        map[string]any{"displayName": node.DisplayName, "nodeId": node.NodeID},
	})
}

func (h *Handler) loginAdmin(w http.ResponseWriter, r *http.Request) {
	if err := sameOrigin(r); err != nil {
		h.fail(w, err)
		return
	}
	if !h.db.IsInitialized() {
		h.fail(w, apperror.New(409, "setup_required", "Source Node must be initialized first."))
		return
	}
	if !h.login.Take(remoteAddress(r)) {
		h.fail(w, apperror.New(429, "too_many_attempts", "Too many login attempts. Try again later."))
		return
	}
	var body struct {
		Password string `json:"password"`
	}
	if err := readJSON(r, &body); err != nil {
		h.fail(w, err)
		return
	}
	node, err := h.db.GetNodeState(true)
	if err != nil {
		h.fail(w, err)
		return
	}
	if !security.VerifyPassword(body.Password, node.AdminPasswordHash) {
		h.fail(w, apperror.New(401, "invalid_admin_credentials", "Incorrect admin password."))
		return
	}
	token, adminSession, err := h.sessions.create()
	if err != nil {
		h.fail(w, err)
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name: cookieName, Value: token, Path: "/", HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
		MaxAge:   int(h.cfg.AdminSessionTTL / time.Second),
	})
	adminJSON(w, http.StatusOK, map[string]any{"authenticated": true, "csrfToken": adminSession.csrf})
}

func (h *Handler) logout(w http.ResponseWriter, r *http.Request) {
	if err := sameOrigin(r); err != nil {
		h.fail(w, err)
		return
	}
	if _, err := h.authenticated(r, true); err != nil {
		h.fail(w, err)
		return
	}
	h.sessions.delete(cookieValue(r))
	http.SetCookie(w, &http.Cookie{
		Name: cookieName, Value: "", Path: "/", HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
		MaxAge:   -1,
	})
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusNoContent)
}

func displayName(value string) (string, error) {
	trimmed := strings.TrimSpace(value)
	if len(trimmed) < 1 || len(trimmed) > 100 {
		return "", apperror.New(400, "invalid_display_name", "Display name must contain 1–100 printable characters.")
	}
	for _, char := range trimmed {
		if unicode.IsControl(char) {
			return "", apperror.New(400, "invalid_display_name", "Display name must contain 1–100 printable characters.")
		}
	}
	return trimmed, nil
}
