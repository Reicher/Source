package httpapi

import "net/http"

func (h *Handler) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

func (h *Handler) status(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"service":      "source-node",
		"apiVersion":   1,
		"llmAvailable": h.ai.Status(r.Context()),
		"ai":           h.ai.Capabilities(),
	})
}
