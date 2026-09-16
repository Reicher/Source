package httpapi

import (
	"net/http"

	"source.local/node/internal/apperror"
	"source.local/node/internal/auth"
	"source.local/node/internal/silver"
	"source.local/node/internal/syncmodel"
)

func (h *Handler) silverRefinements(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	switch r.Method {
	case http.MethodGet:
		jobs, err := h.silver.Jobs(session.User.ID)
		if err != nil {
			h.fail(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"jobs": jobs})
	case http.MethodPost:
		h.refineSilver(w, r, session)
	default:
		h.notFound(w, r)
	}
}

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
	status := http.StatusAccepted
	if result.Job.State == "completed" {
		status = http.StatusOK
	}
	writeJSON(w, status, result)
}

func (h *Handler) silverRefinementJob(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	if r.Method != http.MethodGet {
		h.notFound(w, r)
		return
	}
	jobID := r.PathValue("job")
	if !syncmodel.ValidUUID(jobID) {
		h.fail(w, apperror.New(400, "invalid_refinement_job", "The refinement job identifier is invalid."))
		return
	}
	job, err := h.silver.Job(session.User.ID, jobID)
	if err != nil {
		h.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, job)
}

func (h *Handler) controlSilverRefinementJob(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	if r.Method != http.MethodPost {
		h.notFound(w, r)
		return
	}
	jobID := r.PathValue("job")
	if !syncmodel.ValidUUID(jobID) {
		h.fail(w, apperror.New(400, "invalid_refinement_job", "The refinement job identifier is invalid."))
		return
	}
	var job silver.Job
	var err error
	switch r.PathValue("action") {
	case "pause":
		job, err = h.silver.Pause(session.User.ID, jobID)
	case "resume":
		job, err = h.silver.Resume(session.User.ID, jobID)
	case "cancel":
		job, err = h.silver.Cancel(session.User.ID, jobID)
	case "retry":
		job, err = h.silver.Retry(session.User.ID, jobID)
	default:
		h.notFound(w, r)
		return
	}
	if err != nil {
		h.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, job)
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
