package httpapi

import (
	"net/http"

	"source.local/node/internal/apperror"
	"source.local/node/internal/auth"
	"source.local/node/internal/pairing"
	"source.local/node/internal/security"
)

func (h *Handler) startPairing(w http.ResponseWriter, r *http.Request) {
	var body pairing.StartRequest
	if err := readJSON(r, 64*1024, &body); err != nil {
		h.fail(w, err)
		return
	}
	value, err := h.pairing.Start(body)
	if err != nil {
		h.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, value)
}

func (h *Handler) completePairing(w http.ResponseWriter, r *http.Request) {
	var body pairing.CompleteRequest
	if err := readJSON(r, 64*1024, &body); err != nil {
		h.fail(w, err)
		return
	}
	value, err := h.pairing.Complete(body)
	if err != nil {
		h.fail(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, value)
}

func (h *Handler) setupRecovery(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	var body struct {
		RecoveryKey      string `json:"recoveryKey"`
		RecoveryEnvelope string `json:"recoveryEnvelope"`
	}
	if err := readJSON(r, 64*1024, &body); err != nil {
		h.fail(w, err)
		return
	}
	if !validExact(body.RecoveryKey, 43) || !validExact(body.RecoveryEnvelope, 80) {
		h.fail(w, apperror.New(400, "invalid_recovery_material", "Recovery material is invalid."))
		return
	}
	ok, err := h.db.ConfigureRecovery(session.User.ID, security.TokenHash(body.RecoveryKey), body.RecoveryEnvelope)
	if err != nil {
		h.fail(w, err)
		return
	}
	if !ok {
		h.fail(w, apperror.New(409, "recovery_already_configured", "The recovery key is already configured."))
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"recoveryConfigured": true})
}

func validExact(value string, length int) bool {
	if len(value) != length {
		return false
	}
	for _, char := range value {
		if !(char >= 'a' && char <= 'z' || char >= 'A' && char <= 'Z' || char >= '0' && char <= '9' || char == '_' || char == '-') {
			return false
		}
	}
	return true
}
