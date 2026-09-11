package httpapi

import (
	"net/http"
	"strings"

	"source.local/node/internal/apperror"
	"source.local/node/internal/auth"
)

func (h *Handler) libraryItem(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPut:
		h.requireClient(h.putLibraryItem)(w, r)
	case http.MethodDelete:
		h.requireClient(h.deleteLibraryItem)(w, r)
	default:
		h.unmatched(w, r)
	}
}

func (h *Handler) putLibraryItem(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	if !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/octet-stream") {
		h.fail(w, apperror.New(415, "unsupported_media_type", "The item must use application/octet-stream."))
		return
	}
	item, created, err := h.storage.PutLibraryItem(
		session.User.ID,
		r.PathValue("item"),
		strings.ToLower(r.Header.Get("X-Source-Content-SHA256")),
		strings.ToLower(r.Header.Get("X-Content-SHA256")),
		r.ContentLength,
		r.Body,
		h.cfg.MaximumLibraryItemBytes,
	)
	if err != nil {
		h.fail(w, err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	w.Header().Set("Location", r.URL.Path)
	writeJSON(w, status, map[string]any{"item": item})
}

func (h *Handler) deleteLibraryItem(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	if err := h.storage.DeleteLibraryItem(
		session.User.ID,
		r.PathValue("item"),
		strings.ToLower(r.Header.Get("X-Source-Content-SHA256")),
	); err != nil {
		h.fail(w, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusNoContent)
}
