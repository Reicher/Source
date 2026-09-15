package httpapi

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"source.local/node/internal/apperror"
	"source.local/node/internal/auth"
	"source.local/node/internal/database"
	"source.local/node/internal/syncmodel"
)

func (h *Handler) commitSyncMutation(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	if !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/octet-stream") {
		h.fail(w, apperror.New(415, "unsupported_media_type", "Canonical mutations must use application/octet-stream."))
		return
	}
	operationID := r.PathValue("operation")
	if !syncmodel.ValidUUID(operationID) {
		h.notFound(w, r)
		return
	}
	contractVersion, ok := requiredPositiveInt64(w, r, "X-Source-Contract-Version")
	if !ok {
		return
	}
	originSequence, ok := requiredPositiveInt64(w, r, "X-Source-Origin-Sequence")
	if !ok {
		return
	}
	parents := []string{}
	if raw := r.Header.Get("X-Source-Parent-Revisions"); raw != "" {
		parents = strings.Split(raw, ",")
	}
	revision := syncmodel.Revision{
		RevisionID: r.Header.Get("X-Source-Revision-Id"),
		ObjectKey: syncmodel.ObjectKey{
			ProfileID: session.User.ID, Collection: r.Header.Get("X-Source-Collection"),
			ObjectID: r.Header.Get("X-Source-Object-Id"),
		},
		Kind: r.Header.Get("X-Source-Revision-Kind"), ParentRevisionIDs: parents,
	}
	if raw := r.Header.Get("X-Source-Created-At-Millis"); raw != "" {
		createdAt, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || createdAt <= 0 {
			h.fail(w, apperror.New(400, "invalid_mutation_header", "X-Source-Created-At-Millis must be a positive integer."))
			return
		}
		revision.CreatedAtMillis = &createdAt
	}
	if revision.Kind == "content" {
		formatVersion, valid := requiredPositiveInt64(w, r, "X-Source-Payload-Format-Version")
		if !valid {
			return
		}
		byteCount, err := strconv.ParseInt(r.Header.Get("X-Source-Byte-Count"), 10, 64)
		if err != nil || byteCount < 0 {
			h.fail(w, apperror.New(400, "invalid_mutation_header", "X-Source-Byte-Count must be a non-negative integer."))
			return
		}
		if r.ContentLength >= 0 && r.ContentLength != byteCount {
			h.fail(w, apperror.New(400, "payload_size_mismatch", "The request size does not match the payload descriptor."))
			return
		}
		revision.Payload = &syncmodel.Payload{
			Format: r.Header.Get("X-Source-Payload-Format"), FormatVersion: int(formatVersion),
			ByteCount: byteCount, PlaintextSHA256: r.Header.Get("X-Source-Plaintext-SHA256"),
		}
	}
	mutation := syncmodel.Mutation{
		ContractVersion: int(contractVersion), OperationID: operationID,
		OriginID: session.ClientID, OriginEpoch: r.Header.Get("X-Source-Origin-Epoch"),
		OriginSequence: originSequence, ExpectedAuthorityEpoch: r.Header.Get("X-Source-Authority-Epoch"),
		Revision: revision,
	}
	node, err := h.db.GetNodeState(false)
	if err != nil {
		h.fail(w, err)
		return
	}
	maximumBytes := h.cfg.MaximumSnapshotBytes
	if revision.ObjectKey.Collection == "library-items" {
		maximumBytes = h.cfg.MaximumLibraryItemBytes
	}
	receipt, heads, created, err := h.storage.CommitMutation(
		session.User.ID, session.ClientID, node.NodeID, mutation, r.Body, maximumBytes,
	)
	if err != nil {
		h.fail(w, err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, map[string]any{
		"contractVersion": syncmodel.ContractVersion, "receipt": receipt, "heads": heads,
	})
}

func (h *Handler) syncChanges(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	var request syncmodel.ChangesRequest
	if err := readJSON(r, 16*1024, &request); err != nil {
		h.fail(w, err)
		return
	}
	if request.ContractVersion != syncmodel.ContractVersion || !syncmodel.ValidCollection(request.Collection) || !syncmodel.ValidObjectID(request.ObjectID) {
		h.fail(w, apperror.New(400, "invalid_sync_request", "The synchronization request is invalid."))
		return
	}
	node, err := h.db.GetNodeState(false)
	if err != nil {
		h.fail(w, err)
		return
	}
	state, err := h.db.SyncState(session.User.ID, node.NodeID)
	if err != nil {
		h.fail(w, mapDatabaseSyncFailure(err))
		return
	}
	through := state.NextSequence - 1
	requiresManifest := request.Cursor == nil ||
		request.Cursor.AuthorityNodeID != state.AuthorityNodeID ||
		request.Cursor.AuthorityEpoch != state.AuthorityEpoch ||
		request.Cursor.CommitSequence < state.RetainedLogFloor ||
		request.Cursor.CommitSequence > through
	changes := []syncmodel.Change{}
	if !requiresManifest {
		changes, err = h.db.SyncChanges(
			session.User.ID, request.Collection, request.ObjectID, state.AuthorityEpoch,
			request.Cursor.CommitSequence,
		)
		if err != nil {
			h.fail(w, err)
			return
		}
		for index := range changes {
			changes[index].Receipt.AuthorityNodeID = state.AuthorityNodeID
		}
	}
	heads, err := h.db.SyncHeads(session.User.ID, request.Collection, request.ObjectID)
	if err != nil {
		h.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, syncmodel.ChangesResponse{
		ContractVersion:  syncmodel.ContractVersion,
		Cursor:           syncmodel.Cursor{AuthorityNodeID: state.AuthorityNodeID, AuthorityEpoch: state.AuthorityEpoch, CommitSequence: through},
		RequiresManifest: requiresManifest, Changes: changes, Heads: heads,
	})
}

func (h *Handler) ackSyncCursor(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	var request syncmodel.AckRequest
	if err := readJSON(r, 8*1024, &request); err != nil {
		h.fail(w, err)
		return
	}
	if request.ContractVersion != syncmodel.ContractVersion || !syncmodel.ValidCollection(request.Collection) ||
		!syncmodel.ValidObjectID(request.ObjectID) || request.Cursor.CommitSequence < 0 {
		h.fail(w, apperror.New(400, "invalid_cursor", "The synchronization cursor is invalid."))
		return
	}
	if err := h.db.AcknowledgeSyncCursor(
		session.User.ID, session.ClientID, request.Collection, request.ObjectID,
		request.Cursor, h.cfg.Now().UnixMilli(),
	); err != nil {
		h.fail(w, mapDatabaseSyncFailure(err))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"contractVersion": syncmodel.ContractVersion, "cursor": request.Cursor})
}

func (h *Handler) syncPayload(w http.ResponseWriter, r *http.Request, session *auth.Session) {
	revisionID := r.PathValue("revision")
	if !syncmodel.ValidSHA256(revisionID) {
		h.notFound(w, r)
		return
	}
	value, revision, err := h.storage.CanonicalPayload(session.User.ID, revisionID)
	if err != nil {
		h.fail(w, err)
		return
	}
	if value == nil || revision == nil {
		h.fail(w, apperror.New(404, "revision_not_found", "The revision does not exist."))
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(revision.Payload.ByteCount, 10))
	w.Header().Set("X-Source-Plaintext-SHA256", revision.Payload.PlaintextSHA256)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(value.Body)
}

func requiredPositiveInt64(w http.ResponseWriter, r *http.Request, header string) (int64, bool) {
	value, err := strconv.ParseInt(r.Header.Get(header), 10, 64)
	if err != nil || value <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": map[string]any{
			"code": "invalid_mutation_header", "message": header + " must be a positive integer.",
		}})
		return 0, false
	}
	return value, true
}

func mapDatabaseSyncFailure(err error) error {
	var failure database.SyncFailure
	if errors.As(err, &failure) {
		status := http.StatusConflict
		if failure.Code == "authority_mismatch" {
			status = http.StatusForbidden
		}
		return apperror.New(status, failure.Code, failure.Message)
	}
	return err
}
