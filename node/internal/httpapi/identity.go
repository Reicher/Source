package httpapi

import (
	"net/http"

	"source.local/node/internal/auth"
)

func (h *Handler) me(w http.ResponseWriter, _ *http.Request, session *auth.Session) {
	writeJSON(w, http.StatusOK, map[string]any{"user": session.User, "clientId": session.ClientID})
}

func (h *Handler) identityChallenge(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	var body struct {
		Protocol int    `json:"protocol"`
		Nonce    string `json:"nonce"`
	}
	if err := readJSON(r, 64*1024, &body); err != nil {
		h.fail(w, err)
		return
	}
	value, err := auth.ProveNodeIdentity(h.db, session, body.Protocol, body.Nonce)
	if err != nil {
		h.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, value)
}
