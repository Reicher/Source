package storage

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"

	"source.local/node/internal/apperror"
	"source.local/node/internal/database"
	"source.local/node/internal/security"
	"source.local/node/internal/syncmodel"
)

func (s *Storage) CommitMutation(
	userID, clientID, nodeID string,
	mutation syncmodel.Mutation,
	body io.Reader,
	maximumBytes int64,
) (syncmodel.CommitReceipt, []syncmodel.Revision, bool, error) {
	if mutation.OriginID != clientID || mutation.Revision.ObjectKey.ProfileID != userID {
		return syncmodel.CommitReceipt{}, nil, false, apperror.New(403, "mutation_authority_mismatch", "The mutation does not belong to the authenticated Client and profile.")
	}
	if strings.HasPrefix(mutation.Revision.ObjectKey.Collection, "silver") {
		return syncmodel.CommitReceipt{}, nil, false, apperror.New(403, "silver_node_authority_required", "Only the authoritative Node may originate persistent Silver.")
	}
	if err := syncmodel.ValidateMutation(mutation); err != nil {
		return syncmodel.CommitReceipt{}, nil, false, apperror.Wrap(400, "invalid_mutation", "The canonical storage mutation is invalid.", err)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.commitMutationLocked(userID, nodeID, mutation, body, maximumBytes)
}

func (s *Storage) commitMutationLocked(
	userID, nodeID string,
	mutation syncmodel.Mutation,
	body io.Reader,
	maximumBytes int64,
) (syncmodel.CommitReceipt, []syncmodel.Revision, bool, error) {
	mutationDigest, err := syncmodel.MutationDigest(mutation)
	if err != nil {
		return syncmodel.CommitReceipt{}, nil, false, err
	}
	revisionForDigest := mutation.Revision
	revisionForDigest.CreatedAtMillis = nil
	revisionDigestBytes, err := json.Marshal(revisionForDigest)
	if err != nil {
		return syncmodel.CommitReceipt{}, nil, false, err
	}
	revisionDigestSum := sha256.Sum256(revisionDigestBytes)
	revisionDigest := hex.EncodeToString(revisionDigestSum[:])

	if existing, digest, findErr := s.db.FindSyncOperation(userID, mutation.OperationID); findErr != nil {
		return syncmodel.CommitReceipt{}, nil, false, findErr
	} else if existing != nil {
		if digest != mutationDigest {
			return syncmodel.CommitReceipt{}, nil, false, apperror.New(409, "idempotency_conflict", "The operation identifier was reused for a different mutation.")
		}
		heads, headsErr := s.db.SyncHeads(userID, mutation.Revision.ObjectKey.Collection, mutation.Revision.ObjectKey.ObjectID)
		return *existing, heads, false, headsErr
	}

	createdPayload := false
	if mutation.Revision.Kind == "content" {
		payload := *mutation.Revision.Payload
		if payload.ByteCount > maximumBytes {
			return syncmodel.CommitReceipt{}, nil, false, apperror.New(413, "storage_payload_too_large", "The canonical payload is too large.")
		}
		if payload.ByteCount < 0 {
			return syncmodel.CommitReceipt{}, nil, false, apperror.New(400, "invalid_payload_size", "The canonical payload size is invalid.")
		}
		if existingRevision, digest, findErr := s.db.FindCanonicalRevision(userID, mutation.Revision.RevisionID); findErr != nil {
			return syncmodel.CommitReceipt{}, nil, false, findErr
		} else if existingRevision != nil && digest != revisionDigest {
			return syncmodel.CommitReceipt{}, nil, false, apperror.New(409, "revision_corrupt", "The revision identifier has conflicting metadata.")
		} else if existingRevision == nil {
			legacyBytes, findErr := s.db.TotalStorageBytes(userID)
			if findErr != nil {
				return syncmodel.CommitReceipt{}, nil, false, findErr
			}
			canonicalBytes, findErr := s.db.CanonicalStorageBytes(userID)
			if findErr != nil {
				return syncmodel.CommitReceipt{}, nil, false, findErr
			}
			user, findErr := s.db.FindUser(userID)
			if findErr != nil {
				return syncmodel.CommitReceipt{}, nil, false, findErr
			}
			if user == nil {
				return syncmodel.CommitReceipt{}, nil, false, apperror.New(404, "user_not_found", "User storage namespace is unavailable.")
			}
			if legacyBytes+canonicalBytes+payload.ByteCount > user.QuotaBytes {
				return syncmodel.CommitReceipt{}, nil, false, apperror.New(413, "storage_quota_exceeded", "User storage quota exceeded")
			}
			createdPayload, err = s.writeCanonicalPayload(userID, mutation.Revision, body)
			if err != nil {
				return syncmodel.CommitReceipt{}, nil, false, err
			}
		} else if err = s.verifyCanonicalPayload(userID, mutation.Revision); err != nil {
			return syncmodel.CommitReceipt{}, nil, false, err
		}
	} else if body != nil {
		one := make([]byte, 1)
		if count, readErr := body.Read(one); readErr != nil && !errors.Is(readErr, io.EOF) {
			return syncmodel.CommitReceipt{}, nil, false, readErr
		} else if count != 0 {
			return syncmodel.CommitReceipt{}, nil, false, apperror.New(400, "tombstone_has_payload", "A tombstone cannot contain payload bytes.")
		}
	}

	receipt, created, err := s.db.CommitSyncMutation(userID, nodeID, mutationDigest, revisionDigest, mutation)
	if err != nil {
		if createdPayload {
			_ = os.Remove(s.canonicalPayloadPath(userID, mutation.Revision))
		}
		return syncmodel.CommitReceipt{}, nil, false, mapSyncFailure(err)
	}
	heads, err := s.db.SyncHeads(userID, mutation.Revision.ObjectKey.Collection, mutation.Revision.ObjectKey.ObjectID)
	return receipt, heads, created, err
}

// CommitNodeValue appends one Node-originated value to the profile's single
// authoritative history. It is intentionally not exposed through the generic
// Client mutation endpoint.
func (s *Storage) CommitNodeValue(
	userID, nodeID, collection, objectID, format string,
	formatVersion int,
	body []byte,
	createdAt int64,
	maximumBytes int64,
) (syncmodel.CommitReceipt, []syncmodel.Revision, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	heads, err := s.db.SyncHeads(userID, collection, objectID)
	if err != nil {
		return syncmodel.CommitReceipt{}, nil, false, err
	}
	if len(heads) > 1 {
		return syncmodel.CommitReceipt{}, heads, false, apperror.New(409, "silver_history_conflict", "The authoritative Silver history has multiple heads.")
	}
	sum := sha256.Sum256(body)
	plaintextSHA := hex.EncodeToString(sum[:])
	if len(heads) == 1 && heads[0].Kind == "content" && heads[0].Payload != nil &&
		heads[0].Payload.PlaintextSHA256 == plaintextSHA && heads[0].Payload.Format == format &&
		heads[0].Payload.FormatVersion == formatVersion {
		return syncmodel.CommitReceipt{RevisionID: heads[0].RevisionID}, heads, false, nil
	}
	state, err := s.db.SyncState(userID, nodeID)
	if err != nil {
		return syncmodel.CommitReceipt{}, nil, false, mapSyncFailure(err)
	}
	sequence, err := s.db.NextOriginSequence(userID, nodeID, state.AuthorityEpoch)
	if err != nil {
		return syncmodel.CommitReceipt{}, nil, false, err
	}
	operationID, err := security.UUID()
	if err != nil {
		return syncmodel.CommitReceipt{}, nil, false, err
	}
	parents := make([]string, len(heads))
	for index := range heads {
		parents[index] = heads[index].RevisionID
	}
	revision := syncmodel.Revision{
		ObjectKey: syncmodel.ObjectKey{ProfileID: userID, Collection: collection, ObjectID: objectID},
		Kind:      "content", ParentRevisionIDs: parents,
		Payload: &syncmodel.Payload{
			Format: format, FormatVersion: formatVersion,
			ByteCount: int64(len(body)), PlaintextSHA256: plaintextSHA,
		},
		CreatedAtMillis: &createdAt,
	}
	revision.RevisionID, err = syncmodel.RevisionID(revision)
	if err != nil {
		return syncmodel.CommitReceipt{}, nil, false, err
	}
	mutation := syncmodel.Mutation{
		ContractVersion: syncmodel.ContractVersion, OperationID: operationID,
		OriginID: nodeID, OriginEpoch: state.AuthorityEpoch, OriginSequence: sequence,
		ExpectedAuthorityEpoch: state.AuthorityEpoch, Revision: revision,
	}
	return s.commitMutationLocked(userID, nodeID, mutation, bytes.NewReader(body), maximumBytes)
}

func (s *Storage) writeCanonicalPayload(userID string, revision syncmodel.Revision, body io.Reader) (bool, error) {
	path := s.canonicalPayloadPath(userID, revision)
	if path == "" {
		return false, apperror.New(404, "user_not_found", "User storage namespace is unavailable.")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return false, err
	}
	token, err := security.UUID()
	if err != nil {
		return false, err
	}
	temporary := filepath.Join(filepath.Dir(path), "."+revision.RevisionID+"."+token+".tmp")
	file, err := os.OpenFile(temporary, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return false, err
	}
	removeTemporary := true
	defer func() {
		_ = file.Close()
		if removeTemporary {
			_ = os.Remove(temporary)
		}
	}()
	hash := sha256.New()
	written, err := io.Copy(io.MultiWriter(file, hash), io.LimitReader(body, revision.Payload.ByteCount+1))
	if err != nil {
		return false, err
	}
	if written != revision.Payload.ByteCount {
		return false, apperror.New(400, "payload_size_mismatch", "The canonical payload size does not match its descriptor.")
	}
	if hex.EncodeToString(hash.Sum(nil)) != revision.Payload.PlaintextSHA256 {
		return false, apperror.New(400, "payload_hash_mismatch", "The canonical payload checksum does not match its descriptor.")
	}
	if err = file.Sync(); err != nil {
		return false, err
	}
	if err = file.Close(); err != nil {
		return false, err
	}
	if err = os.Rename(temporary, path); err != nil {
		return false, err
	}
	removeTemporary = false
	if directory, openErr := os.Open(filepath.Dir(path)); openErr == nil {
		_ = directory.Sync()
		_ = directory.Close()
	}
	return true, nil
}

func (s *Storage) verifyCanonicalPayload(userID string, revision syncmodel.Revision) error {
	file, err := os.Open(s.canonicalPayloadPath(userID, revision))
	if err != nil {
		return err
	}
	defer file.Close()
	hash := sha256.New()
	written, err := io.Copy(hash, file)
	if err != nil {
		return err
	}
	if written != revision.Payload.ByteCount || hex.EncodeToString(hash.Sum(nil)) != revision.Payload.PlaintextSHA256 {
		return apperror.New(500, "stored_payload_corrupt", "The stored canonical payload is corrupt.")
	}
	return nil
}

func (s *Storage) CanonicalPayload(userID, revisionID string) (*Value, *syncmodel.Revision, error) {
	revision, _, err := s.db.FindCanonicalRevision(userID, revisionID)
	if err != nil || revision == nil {
		return nil, revision, err
	}
	if revision.Kind != "content" {
		return nil, revision, apperror.New(404, "payload_not_found", "The revision has no payload.")
	}
	body, err := os.ReadFile(s.canonicalPayloadPath(userID, *revision))
	if os.IsNotExist(err) {
		return nil, revision, apperror.New(500, "stored_payload_missing", "The canonical payload is missing.")
	}
	if err != nil {
		return nil, revision, err
	}
	if int64(len(body)) != revision.Payload.ByteCount {
		return nil, revision, apperror.New(500, "stored_payload_corrupt", "The stored canonical payload is corrupt.")
	}
	return &Value{Body: body}, revision, nil
}

func (s *Storage) canonicalPayloadPath(userID string, revision syncmodel.Revision) string {
	user, err := s.db.FindUser(userID)
	if err != nil || user == nil {
		return ""
	}
	return filepath.Join(s.root, user.StorageNamespace, "canonical", revision.ObjectKey.Collection,
		revision.ObjectKey.ObjectID, revision.RevisionID+".bin")
}

func mapSyncFailure(err error) error {
	var failure database.SyncFailure
	if !errors.As(err, &failure) {
		return err
	}
	status := 409
	if failure.Code == "authority_mismatch" {
		status = 403
	}
	return apperror.New(status, failure.Code, failure.Message)
}
