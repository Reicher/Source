package httpapi

import (
	"net/http"

	"source.local/node/internal/apperror"
	"source.local/node/internal/auth"
	"source.local/node/internal/silver"
)

func (h *Handler) refineSilver(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	if !h.background.Take(session.User.ID) {
		h.fail(w, apperror.New(429, "background_ai_rate_limited", "Too many background AI requests. Wait a moment."))
		return
	}
	var request silver.RefineRequest
	maximum := h.cfg.MaximumLibraryItemBytes + 16*1024
	if maximum < 64*1024 {
		maximum = 64 * 1024
	}
	if err := readJSON(r, maximum, &request); err != nil {
		h.fail(w, err)
		return
	}
	result, err := h.silver.Refine(r.Context(), session.User.ID, request)
	if err != nil {
		h.fail(w, err)
		return
	}
	status := http.StatusOK
	if result.Refined {
		status = http.StatusCreated
	}
	writeJSON(w, status, result)
}

func (h *Handler) removeSilver(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	var request silver.RemoveRequest
	if err := readJSON(r, 16*1024, &request); err != nil {
		h.fail(w, err)
		return
	}
	result, err := h.silver.Remove(session.User.ID, request)
	if err != nil {
		h.fail(w, err)
		return
	}
	status := http.StatusOK
	if result.SilverChanged {
		status = http.StatusCreated
	}
	writeJSON(w, status, result)
}
