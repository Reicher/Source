package httpapi

import "net/http"

func (h *Handler) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

func (h *Handler) status(w http.ResponseWriter, r *http.Request) {
	runtime := h.ai.State(r.Context())
	writeJSON(w, http.StatusOK, map[string]any{
		"service":    "source-node",
		"apiVersion": 1,
		"aiRuntime":  runtime,
	})
}
