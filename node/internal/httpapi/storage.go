package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"source.local/node/internal/apperror"
	"source.local/node/internal/auth"
	"source.local/node/internal/database"
)

var storageAppPattern = regexp.MustCompile(`^[a-z][a-z0-9-]{1,31}$`)
var snapshotIDPattern = regexp.MustCompile(`(?i)^[0-9a-f-]{36}$`)

func (h *Handler) storageItem(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPut:
		h.requireClient(h.putSnapshot)(w, r)
	case http.MethodDelete:
		h.requireClient(h.deleteSnapshot)(w, r)
	default:
		h.unmatched(w, r)
	}
}

func (h *Handler) listSnapshots(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	app, ok := h.storageApp(w, r)
	if !ok {
		return
	}
	items, err := h.storage.List(session.User.ID, app)
	if err != nil {
		h.fail(w, err)
		return
	}
	if items == nil {
		items = []database.Snapshot{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"snapshots": items})
}

func (h *Handler) latestSnapshot(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	app, ok := h.storageApp(w, r)
	if !ok {
		return
	}
	value, err := h.storage.Latest(session.User.ID, app)
	if err != nil {
		h.fail(w, err)
		return
	}
	if value == nil {
		h.fail(w, apperror.New(404, "snapshot_not_found", "No backup exists."))
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(value.Metadata.Bytes, 10))
	w.Header().Set("X-Snapshot-Id", value.Metadata.ID)
	w.Header().Set("X-Snapshot-Created-At", time.UnixMilli(value.Metadata.CreatedAt).UTC().Format(time.RFC3339Nano))
	w.Header().Set("X-Content-SHA256", value.Metadata.SHA256)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(value.Body)
}

func (h *Handler) putSnapshot(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	app, id, ok := h.storageItemValues(w, r)
	if !ok {
		return
	}
	if !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/octet-stream") {
		h.fail(w, apperror.New(415, "unsupported_media_type", "The snapshot must use application/octet-stream."))
		return
	}
	body, err := readBody(r, h.cfg.MaximumSnapshotBytes)
	if err != nil {
		h.fail(w, err)
		return
	}
	if len(body) < 32 {
		h.fail(w, apperror.New(400, "invalid_snapshot", "The snapshot is too small."))
		return
	}
	sum := sha256.Sum256(body)
	actual := hex.EncodeToString(sum[:])
	declared := r.Header.Get("X-Content-SHA256")
	if declared != "" && strings.ToLower(declared) != actual {
		h.fail(w, apperror.New(400, "snapshot_hash_mismatch", "The snapshot checksum does not match."))
		return
	}
	metadata, err := h.storage.Put(session.User.ID, app, id, body)
	if err != nil {
		h.fail(w, err)
		return
	}
	w.Header().Set("Location", r.URL.Path)
	writeJSON(w, http.StatusCreated, map[string]any{"snapshot": metadata})
}

func (h *Handler) deleteSnapshot(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	app, id, ok := h.storageItemValues(w, r)
	if !ok {
		return
	}
	deleted, err := h.storage.Delete(session.User.ID, app, id)
	if err != nil {
		h.fail(w, err)
		return
	}
	if !deleted {
		h.fail(w, apperror.New(404, "snapshot_not_found", "The backup does not exist."))
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) storageApp(w http.ResponseWriter, r *http.Request) (string, bool) {
	app := r.PathValue("app")
	if !storageAppPattern.MatchString(app) {
		h.notFound(w, r)
		return "", false
	}
	if _, allowed := h.cfg.AllowedStorageApps[app]; !allowed {
		h.fail(w, apperror.New(403, "app_not_allowed", "The app does not have access to storage."))
		return "", false
	}
	return app, true
}

func (h *Handler) storageItemValues(w http.ResponseWriter, r *http.Request) (string, string, bool) {
	app, ok := h.storageApp(w, r)
	if !ok {
		return "", "", false
	}
	id := r.PathValue("snapshot")
	if !snapshotIDPattern.MatchString(id) {
		h.notFound(w, r)
		return "", "", false
	}
	return app, id, true
}
