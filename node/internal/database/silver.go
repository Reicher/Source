package database

import (
	"database/sql"
	"errors"
	"strings"
)

type SilverRefinementSource struct {
	SourceID, Name, SourceType, ContentSHA256, Plaintext string
	AcceptedAt                                           int64
	RefinedProcessorVersion, RefinedModelID              *string
	RefinedAt                                            *int64
	RemovedAt                                            *int64
}

// AcceptSilverRefinementSource durably records Client-originated plaintext
// Bronze before refinement starts. operationID is safe to retry, while source
// identity prevents a reconnect from creating a second logical input.
func (d *DB) AcceptSilverRefinementSource(
	userID, operationID, requestDigest string,
	source SilverRefinementSource,
) (SilverRefinementSource, bool, error) {
	tx, err := d.sql.Begin()
	if err != nil {
		return SilverRefinementSource{}, false, err
	}
	defer tx.Rollback()

	var priorDigest, priorSourceID string
	err = tx.QueryRow(`SELECT request_digest,source_id FROM silver_refinement_operations
WHERE user_id=? AND operation_id=?`, userID, operationID).Scan(&priorDigest, &priorSourceID)
	if err == nil {
		if priorDigest != requestDigest || priorSourceID != source.SourceID {
			return SilverRefinementSource{}, false, SyncFailure{"idempotency_conflict", "The operation identifier was reused for different Bronze material."}
		}
		stored, findErr := findSilverRefinementSourceTx(tx, userID, source.SourceID)
		if findErr != nil {
			return SilverRefinementSource{}, false, findErr
		}
		return stored, false, tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return SilverRefinementSource{}, false, err
	}

	stored, err := findSilverRefinementSourceTx(tx, userID, source.SourceID)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return SilverRefinementSource{}, false, err
	}
	changed := errors.Is(err, sql.ErrNoRows) || stored.ContentSHA256 != source.ContentSHA256 || stored.RemovedAt != nil
	if errors.Is(err, sql.ErrNoRows) {
		_, err = tx.Exec(`INSERT INTO silver_refinement_sources(
user_id,source_id,source_name,source_type,content_sha256,plaintext,accepted_at)
VALUES(?,?,?,?,?,?,?)`, userID, source.SourceID, source.Name, source.SourceType,
			source.ContentSHA256, source.Plaintext, source.AcceptedAt)
	} else if changed {
		_, err = tx.Exec(`UPDATE silver_refinement_sources SET
source_name=?,source_type=?,content_sha256=?,plaintext=?,accepted_at=?,
refined_processor_version=NULL,refined_model_id=NULL,refined_at=NULL,removed_at=NULL
WHERE user_id=? AND source_id=?`, source.Name, source.SourceType, source.ContentSHA256,
			source.Plaintext, source.AcceptedAt, userID, source.SourceID)
	} else {
		_, err = tx.Exec(`UPDATE silver_refinement_sources SET source_name=?,source_type=?
WHERE user_id=? AND source_id=?`, source.Name, source.SourceType, userID, source.SourceID)
	}
	if err != nil {
		return SilverRefinementSource{}, false, err
	}
	if changed {
		// A newer accepted generation supersedes any interrupted removal for the
		// previous generation. Replaying that removal must not delete this source.
		if _, err = tx.Exec(`UPDATE silver_refinement_operations SET completed_at=?
WHERE user_id=? AND source_id=? AND operation_kind='removal' AND completed_at IS NULL`,
			source.AcceptedAt, userID, source.SourceID); err != nil {
			return SilverRefinementSource{}, false, err
		}
	}
	if _, err = tx.Exec(`INSERT INTO silver_refinement_operations(
user_id,operation_id,request_digest,source_id,operation_kind) VALUES(?,?,?,?,'refinement')`,
		userID, operationID, requestDigest, source.SourceID); err != nil {
		return SilverRefinementSource{}, false, err
	}
	stored, err = findSilverRefinementSourceTx(tx, userID, source.SourceID)
	if err != nil {
		return SilverRefinementSource{}, false, err
	}
	return stored, changed, tx.Commit()
}

func findSilverRefinementSourceTx(tx *sql.Tx, userID, sourceID string) (SilverRefinementSource, error) {
	var source SilverRefinementSource
	err := tx.QueryRow(`SELECT source_id,source_name,source_type,content_sha256,plaintext,
accepted_at,refined_processor_version,refined_model_id,refined_at,removed_at
FROM silver_refinement_sources WHERE user_id=? AND source_id=?`, userID, sourceID).Scan(
		&source.SourceID, &source.Name, &source.SourceType, &source.ContentSHA256,
		&source.Plaintext, &source.AcceptedAt, &source.RefinedProcessorVersion,
		&source.RefinedModelID, &source.RefinedAt, &source.RemovedAt,
	)
	return source, err
}

func (d *DB) MarkSilverSourceRefined(
	userID, sourceID, contentSHA256, processorVersion, modelID string,
	now int64,
) error {
	result, err := d.sql.Exec(`UPDATE silver_refinement_sources SET
refined_processor_version=?,refined_model_id=?,refined_at=?
WHERE user_id=? AND source_id=? AND content_sha256=?`,
		processorVersion, modelID, now, userID, sourceID, contentSHA256)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return errors.New("Bronze changed before Silver refinement completed")
	}
	return nil
}

func (d *DB) RemoveSilverRefinementSource(
	userID, operationID, requestDigest, sourceID string,
	now int64,
) (bool, bool, error) {
	tx, err := d.sql.Begin()
	if err != nil {
		return false, false, err
	}
	defer tx.Rollback()
	var priorDigest, priorSourceID, priorKind string
	var requiresSilverChange int
	var completedAt *int64
	err = tx.QueryRow(`SELECT request_digest,source_id,operation_kind,requires_silver_change,completed_at
FROM silver_refinement_operations WHERE user_id=? AND operation_id=?`, userID, operationID).
		Scan(&priorDigest, &priorSourceID, &priorKind, &requiresSilverChange, &completedAt)
	if err == nil {
		if priorDigest != requestDigest || priorSourceID != sourceID || priorKind != "removal" {
			return false, false, SyncFailure{"idempotency_conflict", "The operation identifier was reused for a different Silver removal."}
		}
		return false, requiresSilverChange == 1 && completedAt == nil, tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return false, false, err
	}
	var removedAt *int64
	err = tx.QueryRow(`SELECT removed_at FROM silver_refinement_sources
WHERE user_id=? AND source_id=?`, userID, sourceID).Scan(&removedAt)
	changed := errors.Is(err, sql.ErrNoRows) || removedAt == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return false, false, err
	}
	if errors.Is(err, sql.ErrNoRows) {
		_, err = tx.Exec(`INSERT INTO silver_refinement_sources(
user_id,source_id,source_name,source_type,content_sha256,plaintext,accepted_at,removed_at)
VALUES(?,?, '', '', ?, '', ?, ?)`, userID, sourceID, strings.Repeat("0", 64), now, now)
	} else if changed {
		_, err = tx.Exec(`UPDATE silver_refinement_sources SET plaintext='',removed_at=?,
refined_processor_version=NULL,refined_model_id=NULL,refined_at=NULL
WHERE user_id=? AND source_id=?`, now, userID, sourceID)
	}
	if err != nil {
		return false, false, err
	}
	completedAt = nil
	if !changed {
		completedAt = &now
	}
	_, err = tx.Exec(`INSERT INTO silver_refinement_operations(
user_id,operation_id,request_digest,source_id,operation_kind,requires_silver_change,completed_at)
VALUES(?,?,?,?,'removal',?,?)`, userID, operationID, requestDigest, sourceID, changed, completedAt)
	if err != nil {
		return false, false, err
	}
	return changed, changed, tx.Commit()
}

func (d *DB) MarkSilverRemovalComplete(userID, operationID string, now int64) error {
	result, err := d.sql.Exec(`UPDATE silver_refinement_operations SET completed_at=?
WHERE user_id=? AND operation_id=? AND operation_kind='removal' AND completed_at IS NULL`,
		now, userID, operationID)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count > 1 {
		return errors.New("multiple Silver removal operations were completed")
	}
	return nil
}

func (d *DB) NextOriginSequence(userID, originID, originEpoch string) (int64, error) {
	var last int64
	err := d.sql.QueryRow(`SELECT COALESCE(MAX(origin_sequence),0) FROM storage_operations
WHERE user_id=? AND origin_id=? AND origin_epoch=?`, userID, originID, originEpoch).Scan(&last)
	return last + 1, err
}

func (d *DB) SilverRefinementBytesExcept(userID, sourceID string) (int64, error) {
	var bytes int64
	err := d.sql.QueryRow(`SELECT COALESCE(SUM(length(CAST(plaintext AS BLOB))),0)
FROM silver_refinement_sources WHERE user_id=? AND source_id<>?`, userID, sourceID).Scan(&bytes)
	return bytes, err
}

func (d *DB) SilverRefinementBytes(userID string) (int64, error) {
	var bytes int64
	err := d.sql.QueryRow(`SELECT COALESCE(SUM(length(CAST(plaintext AS BLOB))),0)
FROM silver_refinement_sources WHERE user_id=?`, userID).Scan(&bytes)
	return bytes, err
}
