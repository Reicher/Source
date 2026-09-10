package admin

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	qrcode "github.com/skip2/go-qrcode"
	"source.local/node/internal/apperror"
)

func (h *Handler) activeInvitation(w http.ResponseWriter, r *http.Request) {
	if _, err := h.authenticated(r, false); err != nil {
		h.fail(w, err)
		return
	}
	value, err := h.pairing.GetInvitation()
	if err != nil {
		h.fail(w, err)
		return
	}
	adminJSON(w, http.StatusOK, map[string]any{"invitation": value})
}

func (h *Handler) createPairingInvitation(w http.ResponseWriter, r *http.Request) {
	if err := h.mutating(r); err != nil {
		h.fail(w, err)
		return
	}
	var body struct {
		QuotaBytes int64 `json:"quotaBytes"`
	}
	if err := readJSON(r, &body); err != nil {
		h.fail(w, err)
		return
	}
	value, err := h.pairing.CreateInvitation(body.QuotaBytes)
	if err != nil {
		h.fail(w, err)
		return
	}
	adminJSON(w, http.StatusCreated, map[string]any{"invitation": value})
}

func (h *Handler) createRecoveryInvitation(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validPathID(id) {
		h.notFound(w)
		return
	}
	if err := h.mutating(r); err != nil {
		h.fail(w, err)
		return
	}
	value, err := h.pairing.CreateRecoveryInvitation(id)
	if err != nil {
		h.fail(w, err)
		return
	}
	adminJSON(w, http.StatusCreated, map[string]any{"invitation": value})
}

func (h *Handler) deleteUser(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validPathID(id) {
		h.notFound(w)
		return
	}
	if err := h.mutating(r); err != nil {
		h.fail(w, err)
		return
	}
	var body struct {
		DisplayName string `json:"displayName"`
	}
	if err := readJSON(r, &body); err != nil {
		h.fail(w, err)
		return
	}
	user, err := h.db.FindUser(id)
	if err != nil {
		h.fail(w, err)
		return
	}
	if user == nil {
		h.fail(w, apperror.New(404, "user_not_found", "The user was not found."))
		return
	}
	if body.DisplayName != user.DisplayName {
		h.fail(w, apperror.New(400, "delete_confirmation_failed", "The user name confirmation does not match."))
		return
	}
	h.pairing.CancelUserInvitation(user.ID)
	disabled, err := h.db.DisableUser(user.ID, h.cfg.Now().UnixMilli())
	if err != nil {
		h.fail(w, err)
		return
	}
	root := filepath.Clean(h.cfg.StorageRoot)
	userStorage := filepath.Join(root, disabled.StorageNamespace)
	if filepath.Dir(userStorage) != root {
		h.fail(w, apperror.New(500, "invalid_storage_path", "User storage path is invalid."))
		return
	}
	if err = os.RemoveAll(userStorage); err != nil {
		h.fail(w, err)
		return
	}
	if _, err = h.db.DeleteUser(user.ID); err != nil {
		h.fail(w, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) cancelPairingInvitation(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validPathID(id) {
		h.notFound(w)
		return
	}
	if err := h.mutating(r); err != nil {
		h.fail(w, err)
		return
	}
	if _, err := h.pairing.CancelInvitation(id); err != nil {
		h.fail(w, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) pairingInvitationQR(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validPathID(id) {
		h.notFound(w)
		return
	}
	if _, err := h.authenticated(r, false); err != nil {
		h.fail(w, err)
		return
	}
	value, err := h.pairing.GetInvitation()
	if err != nil {
		h.fail(w, err)
		return
	}
	if value == nil || value["state"] != "active" || value["id"] != id {
		h.fail(w, apperror.New(404, "invitation_not_found", "No active invitation was found."))
		return
	}
	svg, err := qrSVG(value["payload"].(string))
	if err != nil {
		h.fail(w, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "image/svg+xml; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(svg)))
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(svg))
}

func qrSVG(payload string) (string, error) {
	code, err := qrcode.New(payload, qrcode.Medium)
	if err != nil {
		return "", err
	}
	bitmap := code.Bitmap()
	scale := 4
	size := len(bitmap) * scale
	var builder strings.Builder
	fmt.Fprintf(&builder, `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="360" height="360"><rect width="100%%" height="100%%" fill="white"/>`, size, size)
	for y, row := range bitmap {
		for x, dark := range row {
			if dark {
				fmt.Fprintf(&builder, `<rect x="%d" y="%d" width="%d" height="%d" fill="black"/>`, x*scale, y*scale, scale, scale)
			}
		}
	}
	builder.WriteString(`</svg>`)
	return builder.String(), nil
}
