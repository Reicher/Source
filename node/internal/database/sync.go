package database

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"sort"

	"source.local/node/internal/security"
	"source.local/node/internal/syncmodel"
)

type SyncState struct {
	AuthorityNodeID  string
	AuthorityEpoch   string
	NextSequence     int64
	RetainedLogFloor int64
}

type SyncFailure struct {
	Code    string
	Message string
}

func (e SyncFailure) Error() string { return e.Message }

func (d *DB) SyncState(userID, nodeID string) (SyncState, error) {
	tx, err := d.sql.Begin()
	if err != nil {
		return SyncState{}, err
	}
	defer tx.Rollback()
	state, err := ensureSyncState(tx, userID, nodeID)
	if err != nil {
		return SyncState{}, err
	}
	return state, tx.Commit()
}

// RotateSyncEpochs starts a new authority history after an explicit rollback
// restore. Existing heads remain the restored manifest, while old cursors and
// receipts can no longer be mistaken for acknowledgements in the new history.
func (d *DB) RotateSyncEpochs() (string, int64, error) {
	epoch, err := security.UUID()
	if err != nil {
		return "", 0, err
	}
	tx, err := d.sql.Begin()
	if err != nil {
		return "", 0, err
	}
	defer tx.Rollback()
	result, err := tx.Exec(`UPDATE profile_sync_state
SET authority_epoch=?,next_commit_sequence=1,retained_log_floor=0`, epoch)
	if err != nil {
		return "", 0, err
	}
	if _, err = tx.Exec(`DELETE FROM client_sync_cursors`); err != nil {
		return "", 0, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return "", 0, err
	}
	if err = tx.Commit(); err != nil {
		return "", 0, err
	}
	return epoch, count, nil
}

func ensureSyncState(tx *sql.Tx, userID, nodeID string) (SyncState, error) {
	var state SyncState
	err := tx.QueryRow(`SELECT authority_node_id,authority_epoch,next_commit_sequence,retained_log_floor
FROM profile_sync_state WHERE user_id=?`, userID).Scan(
		&state.AuthorityNodeID, &state.AuthorityEpoch, &state.NextSequence, &state.RetainedLogFloor,
	)
	if err == nil {
		if state.AuthorityNodeID != nodeID {
			return SyncState{}, SyncFailure{"authority_mismatch", "The profile belongs to a different authoritative Node."}
		}
		return state, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return SyncState{}, err
	}
	epoch, err := security.UUID()
	if err != nil {
		return SyncState{}, err
	}
	if _, err = tx.Exec(`INSERT INTO profile_sync_state(user_id,authority_node_id,authority_epoch,next_commit_sequence)
VALUES(?,?,?,1)`, userID, nodeID, epoch); err != nil {
		return SyncState{}, err
	}
	return SyncState{AuthorityNodeID: nodeID, AuthorityEpoch: epoch, NextSequence: 1}, nil
}

func (d *DB) FindSyncOperation(userID, operationID string) (*syncmodel.CommitReceipt, string, error) {
	var receipt syncmodel.CommitReceipt
	var digest string
	err := d.sql.QueryRow(`SELECT mutation_digest,revision_id,authority_node_id,authority_epoch,commit_sequence
FROM storage_operations WHERE user_id=? AND operation_id=?`, userID, operationID).Scan(
		&digest, &receipt.RevisionID, &receipt.AuthorityNodeID, &receipt.AuthorityEpoch, &receipt.CommitSequence,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, "", nil
	}
	if err != nil {
		return nil, "", err
	}
	receipt.OperationID = operationID
	return &receipt, digest, nil
}

func (d *DB) FindCanonicalRevision(userID, revisionID string) (*syncmodel.Revision, string, error) {
	row := d.sql.QueryRow(`SELECT collection,object_id,kind,parents_json,payload_format,payload_format_version,
byte_count,plaintext_sha256,created_at,revision_digest
FROM storage_revisions WHERE user_id=? AND revision_id=?`, userID, revisionID)
	revision, digest, err := scanRevision(row, userID, revisionID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, "", nil
	}
	return revision, digest, err
}

type rowScanner interface{ Scan(...any) error }

func scanRevision(row rowScanner, userID, revisionID string) (*syncmodel.Revision, string, error) {
	var revision syncmodel.Revision
	var parentsJSON string
	var format sql.NullString
	var formatVersion sql.NullInt64
	var bytes sql.NullInt64
	var plaintextSHA sql.NullString
	var createdAt sql.NullInt64
	var digest string
	err := row.Scan(
		&revision.ObjectKey.Collection, &revision.ObjectKey.ObjectID, &revision.Kind, &parentsJSON,
		&format, &formatVersion, &bytes, &plaintextSHA, &createdAt, &digest,
	)
	if err != nil {
		return nil, "", err
	}
	revision.RevisionID = revisionID
	revision.ObjectKey.ProfileID = userID
	if err = json.Unmarshal([]byte(parentsJSON), &revision.ParentRevisionIDs); err != nil {
		return nil, "", fmt.Errorf("decode revision parents: %w", err)
	}
	if revision.Kind == "content" {
		revision.Payload = &syncmodel.Payload{
			Format: format.String, FormatVersion: int(formatVersion.Int64),
			ByteCount: bytes.Int64, PlaintextSHA256: plaintextSHA.String,
		}
	}
	if createdAt.Valid {
		revision.CreatedAtMillis = &createdAt.Int64
	}
	return &revision, digest, nil
}

func (d *DB) CommitSyncMutation(
	userID, nodeID, mutationDigest, revisionDigest string,
	mutation syncmodel.Mutation,
) (syncmodel.CommitReceipt, bool, error) {
	tx, err := d.sql.Begin()
	if err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	defer tx.Rollback()
	state, err := ensureSyncState(tx, userID, nodeID)
	if err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	if mutation.ExpectedAuthorityEpoch != "" && mutation.ExpectedAuthorityEpoch != state.AuthorityEpoch {
		return syncmodel.CommitReceipt{}, false, SyncFailure{"authority_epoch_changed", "The Client must reconcile with the current authority epoch before replaying this mutation."}
	}

	if receipt, digest, found, lookupErr := findOperationTx(tx, userID, mutation.OperationID); lookupErr != nil {
		return syncmodel.CommitReceipt{}, false, lookupErr
	} else if found {
		if digest != mutationDigest {
			return syncmodel.CommitReceipt{}, false, SyncFailure{"idempotency_conflict", "The operation identifier was reused for a different mutation."}
		}
		return receipt, false, tx.Commit()
	}

	var lastOriginSequence int64
	err = tx.QueryRow(`SELECT COALESCE(MAX(origin_sequence),0) FROM storage_operations
WHERE user_id=? AND origin_id=? AND origin_epoch=?`,
		userID, mutation.OriginID, mutation.OriginEpoch,
	).Scan(&lastOriginSequence)
	if err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	if mutation.OriginSequence != lastOriginSequence+1 {
		return syncmodel.CommitReceipt{}, false, SyncFailure{
			"origin_sequence_out_of_order",
			"The origin sequence must be the next journal position without gaps or reordering.",
		}
	}

	existing, existingDigest, err := findRevisionTx(tx, userID, mutation.Revision.RevisionID)
	if err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	if existing != nil {
		if existingDigest != revisionDigest {
			return syncmodel.CommitReceipt{}, false, SyncFailure{"revision_corrupt", "The revision identifier has conflicting metadata."}
		}
		var original syncmodel.CommitReceipt
		err = tx.QueryRow(`SELECT authority_epoch,commit_sequence FROM storage_revisions
WHERE user_id=? AND revision_id=?`, userID, mutation.Revision.RevisionID).Scan(
			&original.AuthorityEpoch, &original.CommitSequence,
		)
		if err != nil {
			return syncmodel.CommitReceipt{}, false, err
		}
		original.OperationID = mutation.OperationID
		original.RevisionID = mutation.Revision.RevisionID
		original.AuthorityNodeID = state.AuthorityNodeID
		if err = insertOperation(tx, userID, mutationDigest, mutation, original); err != nil {
			return syncmodel.CommitReceipt{}, false, err
		}
		return original, false, tx.Commit()
	}

	for _, parent := range mutation.Revision.ParentRevisionIDs {
		var collection, objectID string
		err = tx.QueryRow(`SELECT collection,object_id FROM storage_revisions WHERE user_id=? AND revision_id=?`, userID, parent).
			Scan(&collection, &objectID)
		if errors.Is(err, sql.ErrNoRows) {
			return syncmodel.CommitReceipt{}, false, SyncFailure{"unknown_parent", "A causal parent is not present on the Node."}
		}
		if err != nil {
			return syncmodel.CommitReceipt{}, false, err
		}
		if collection != mutation.Revision.ObjectKey.Collection || objectID != mutation.Revision.ObjectKey.ObjectID {
			return syncmodel.CommitReceipt{}, false, SyncFailure{"invalid_parent", "A causal parent belongs to a different object."}
		}
	}

	heads, err := headsTx(tx, userID, mutation.Revision.ObjectKey.Collection, mutation.Revision.ObjectKey.ObjectID)
	if err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	var tombstoned int
	err = tx.QueryRow(`SELECT COUNT(*) FROM storage_revisions WHERE user_id=? AND collection=? AND object_id=? AND kind='tombstone'`,
		userID, mutation.Revision.ObjectKey.Collection, mutation.Revision.ObjectKey.ObjectID).Scan(&tombstoned)
	if err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	if tombstoned > 0 && mutation.Revision.Kind == "content" {
		return syncmodel.CommitReceipt{}, false, SyncFailure{"object_deleted", "A deleted object identifier cannot be restored."}
	}
	if mutation.Revision.Kind == "tombstone" && !syncmodel.SameStrings(heads, mutation.Revision.ParentRevisionIDs) {
		return syncmodel.CommitReceipt{}, false, SyncFailure{"delete_conflict", "The deletion does not account for every current head."}
	}

	parentsJSON, err := json.Marshal(mutation.Revision.ParentRevisionIDs)
	if err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	sequence := state.NextSequence
	var payloadFormat any
	var payloadFormatVersion any
	var byteCount any
	var plaintextSHA any
	if mutation.Revision.Payload != nil {
		payloadFormat = mutation.Revision.Payload.Format
		payloadFormatVersion = mutation.Revision.Payload.FormatVersion
		byteCount = mutation.Revision.Payload.ByteCount
		plaintextSHA = mutation.Revision.Payload.PlaintextSHA256
	}
	_, err = tx.Exec(`INSERT INTO storage_revisions(
user_id,collection,object_id,revision_id,kind,parents_json,payload_format,payload_format_version,
byte_count,plaintext_sha256,created_at,origin_id,origin_epoch,origin_sequence,authority_epoch,
commit_sequence,operation_id,revision_digest) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		userID, mutation.Revision.ObjectKey.Collection, mutation.Revision.ObjectKey.ObjectID,
		mutation.Revision.RevisionID, mutation.Revision.Kind, string(parentsJSON), payloadFormat,
		payloadFormatVersion, byteCount, plaintextSHA, mutation.Revision.CreatedAtMillis,
		mutation.OriginID, mutation.OriginEpoch, mutation.OriginSequence, state.AuthorityEpoch,
		sequence, mutation.OperationID, revisionDigest,
	)
	if err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	for _, parent := range mutation.Revision.ParentRevisionIDs {
		if _, err = tx.Exec(`DELETE FROM storage_heads WHERE user_id=? AND collection=? AND object_id=? AND revision_id=?`,
			userID, mutation.Revision.ObjectKey.Collection, mutation.Revision.ObjectKey.ObjectID, parent); err != nil {
			return syncmodel.CommitReceipt{}, false, err
		}
	}
	if _, err = tx.Exec(`INSERT INTO storage_heads(user_id,collection,object_id,revision_id) VALUES(?,?,?,?)`,
		userID, mutation.Revision.ObjectKey.Collection, mutation.Revision.ObjectKey.ObjectID, mutation.Revision.RevisionID); err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	if _, err = tx.Exec(`UPDATE profile_sync_state SET next_commit_sequence=? WHERE user_id=? AND next_commit_sequence=?`,
		sequence+1, userID, sequence); err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	receipt := syncmodel.CommitReceipt{
		OperationID: mutation.OperationID, RevisionID: mutation.Revision.RevisionID,
		AuthorityNodeID: state.AuthorityNodeID, AuthorityEpoch: state.AuthorityEpoch,
		CommitSequence: sequence,
	}
	if err = insertOperation(tx, userID, mutationDigest, mutation, receipt); err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	if err = tx.Commit(); err != nil {
		return syncmodel.CommitReceipt{}, false, err
	}
	return receipt, true, nil
}

func findOperationTx(tx *sql.Tx, userID, operationID string) (syncmodel.CommitReceipt, string, bool, error) {
	var receipt syncmodel.CommitReceipt
	var digest string
	err := tx.QueryRow(`SELECT mutation_digest,revision_id,authority_node_id,authority_epoch,commit_sequence
FROM storage_operations WHERE user_id=? AND operation_id=?`, userID, operationID).Scan(
		&digest, &receipt.RevisionID, &receipt.AuthorityNodeID, &receipt.AuthorityEpoch, &receipt.CommitSequence,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return syncmodel.CommitReceipt{}, "", false, nil
	}
	receipt.OperationID = operationID
	return receipt, digest, err == nil, err
}

func findRevisionTx(tx *sql.Tx, userID, revisionID string) (*syncmodel.Revision, string, error) {
	row := tx.QueryRow(`SELECT collection,object_id,kind,parents_json,payload_format,payload_format_version,
byte_count,plaintext_sha256,created_at,revision_digest FROM storage_revisions WHERE user_id=? AND revision_id=?`,
		userID, revisionID)
	revision, digest, err := scanRevision(row, userID, revisionID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, "", nil
	}
	return revision, digest, err
}

func insertOperation(tx *sql.Tx, userID, digest string, mutation syncmodel.Mutation, receipt syncmodel.CommitReceipt) error {
	_, err := tx.Exec(`INSERT INTO storage_operations(user_id,operation_id,mutation_digest,origin_id,origin_epoch,
origin_sequence,revision_id,authority_node_id,authority_epoch,commit_sequence) VALUES(?,?,?,?,?,?,?,?,?,?)`,
		userID, mutation.OperationID, digest, mutation.OriginID, mutation.OriginEpoch,
		mutation.OriginSequence, mutation.Revision.RevisionID, receipt.AuthorityNodeID,
		receipt.AuthorityEpoch, receipt.CommitSequence,
	)
	return err
}

func headsTx(tx *sql.Tx, userID, collection, objectID string) ([]string, error) {
	rows, err := tx.Query(`SELECT revision_id FROM storage_heads WHERE user_id=? AND collection=? AND object_id=? ORDER BY revision_id`,
		userID, collection, objectID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var heads []string
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return nil, err
		}
		heads = append(heads, id)
	}
	return heads, rows.Err()
}

func (d *DB) SyncHeads(userID, collection, objectID string) ([]syncmodel.Revision, error) {
	rows, err := d.sql.Query(`SELECT r.revision_id,r.collection,r.object_id,r.kind,r.parents_json,r.payload_format,
r.payload_format_version,r.byte_count,r.plaintext_sha256,r.created_at,r.revision_digest
FROM storage_heads h JOIN storage_revisions r ON r.user_id=h.user_id AND r.revision_id=h.revision_id
WHERE h.user_id=? AND h.collection=? AND h.object_id=? ORDER BY r.revision_id`, userID, collection, objectID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanRevisions(rows, userID)
}

func (d *DB) SyncChanges(userID, collection, objectID, authorityEpoch string, after int64) ([]syncmodel.Change, error) {
	rows, err := d.sql.Query(`SELECT revision_id,collection,object_id,kind,parents_json,payload_format,
payload_format_version,byte_count,plaintext_sha256,created_at,revision_digest,operation_id,authority_epoch,commit_sequence
FROM storage_revisions WHERE user_id=? AND collection=? AND object_id=? AND authority_epoch=? AND commit_sequence>?
ORDER BY commit_sequence`, userID, collection, objectID, authorityEpoch, after)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	changes := make([]syncmodel.Change, 0)
	for rows.Next() {
		var revisionID, collectionValue, objectValue, kind, parentsJSON, digest string
		var format sql.NullString
		var formatVersion, bytes, createdAt sql.NullInt64
		var plaintextSHA sql.NullString
		var operationID, epoch string
		var sequence int64
		if err = rows.Scan(&revisionID, &collectionValue, &objectValue, &kind, &parentsJSON, &format,
			&formatVersion, &bytes, &plaintextSHA, &createdAt, &digest, &operationID, &epoch, &sequence); err != nil {
			return nil, err
		}
		var parents []string
		if err = json.Unmarshal([]byte(parentsJSON), &parents); err != nil {
			return nil, err
		}
		revision := syncmodel.Revision{
			RevisionID: revisionID, ObjectKey: syncmodel.ObjectKey{ProfileID: userID, Collection: collectionValue, ObjectID: objectValue},
			Kind: kind, ParentRevisionIDs: parents,
		}
		if createdAt.Valid {
			revision.CreatedAtMillis = &createdAt.Int64
		}
		if kind == "content" {
			revision.Payload = &syncmodel.Payload{Format: format.String, FormatVersion: int(formatVersion.Int64), ByteCount: bytes.Int64, PlaintextSHA256: plaintextSHA.String}
		}
		changes = append(changes, syncmodel.Change{
			Receipt:  syncmodel.CommitReceipt{OperationID: operationID, RevisionID: revisionID, AuthorityNodeID: "", AuthorityEpoch: epoch, CommitSequence: sequence},
			Revision: revision,
		})
	}
	return changes, rows.Err()
}

func scanRevisions(rows *sql.Rows, userID string) ([]syncmodel.Revision, error) {
	revisions := make([]syncmodel.Revision, 0)
	for rows.Next() {
		var revisionID, collection, objectID, kind, parentsJSON, digest string
		var format sql.NullString
		var formatVersion, bytes, createdAt sql.NullInt64
		var plaintextSHA sql.NullString
		if err := rows.Scan(&revisionID, &collection, &objectID, &kind, &parentsJSON, &format,
			&formatVersion, &bytes, &plaintextSHA, &createdAt, &digest); err != nil {
			return nil, err
		}
		var parents []string
		if err := json.Unmarshal([]byte(parentsJSON), &parents); err != nil {
			return nil, err
		}
		revision := syncmodel.Revision{RevisionID: revisionID,
			ObjectKey: syncmodel.ObjectKey{ProfileID: userID, Collection: collection, ObjectID: objectID},
			Kind:      kind, ParentRevisionIDs: parents,
		}
		if createdAt.Valid {
			revision.CreatedAtMillis = &createdAt.Int64
		}
		if kind == "content" {
			revision.Payload = &syncmodel.Payload{Format: format.String, FormatVersion: int(formatVersion.Int64), ByteCount: bytes.Int64, PlaintextSHA256: plaintextSHA.String}
		}
		revisions = append(revisions, revision)
	}
	return revisions, rows.Err()
}

func (d *DB) AcknowledgeSyncCursor(userID, clientID, collection, objectID string, cursor syncmodel.Cursor, now int64) error {
	state, err := d.SyncState(userID, cursor.AuthorityNodeID)
	if err != nil {
		return err
	}
	if state.AuthorityEpoch != cursor.AuthorityEpoch || cursor.CommitSequence < state.RetainedLogFloor || cursor.CommitSequence >= state.NextSequence {
		return SyncFailure{"invalid_cursor", "The synchronization cursor is not valid for this authority history."}
	}
	_, err = d.sql.Exec(`INSERT INTO client_sync_cursors(user_id,client_id,collection,object_id,authority_node_id,authority_epoch,commit_sequence,acknowledged_at)
VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(user_id,client_id,collection,object_id) DO UPDATE SET
authority_node_id=excluded.authority_node_id,authority_epoch=excluded.authority_epoch,
commit_sequence=CASE
WHEN client_sync_cursors.authority_node_id=excluded.authority_node_id
 AND client_sync_cursors.authority_epoch=excluded.authority_epoch
THEN MAX(client_sync_cursors.commit_sequence,excluded.commit_sequence)
ELSE excluded.commit_sequence END,
acknowledged_at=excluded.acknowledged_at`,
		userID, clientID, collection, objectID, cursor.AuthorityNodeID, cursor.AuthorityEpoch, cursor.CommitSequence, now)
	return err
}

func (d *DB) CanonicalStorageBytes(userID string) (int64, error) {
	var bytes int64
	err := d.sql.QueryRow(`SELECT COALESCE(SUM(byte_count),0) FROM storage_revisions WHERE user_id=? AND kind='content'`, userID).Scan(&bytes)
	return bytes, err
}

func SortRevisionsByID(revisions []syncmodel.Revision) {
	sort.Slice(revisions, func(i, j int) bool { return revisions[i].RevisionID < revisions[j].RevisionID })
}
