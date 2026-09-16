package database

import (
	"database/sql"
	"errors"
)

type SilverRefinementJob struct {
	UserID, JobID, RequestDigest         string
	SourceID, SourceName, SourceType     string
	ContentSHA256, Plaintext             string
	ProcessorID, ProcessorVersion        string
	ModelID, OutputLayer, State          string
	Sequence                             int64
	CompletedBatches, TotalBatches       int
	AcceptedAt, UpdatedAt                int64
	StartedAt, CompletedAt               *int64
	ErrorCode, ErrorMessage, ReceiptJSON *string
}

type SilverRefinementCheckpoint struct {
	BatchIndex      int
	BatchContentSHA string
	OutputJSON      string
	CompletedAt     int64
}

const silverJobColumns = `sequence,user_id,job_id,request_digest,source_id,source_name,source_type,
content_sha256,plaintext,processor_id,processor_version,model_id,output_layer,state,completed_batches,total_batches,
accepted_at,started_at,updated_at,completed_at,error_code,error_message,receipt_json`

func scanSilverJob(scanner interface{ Scan(...any) error }) (SilverRefinementJob, error) {
	var job SilverRefinementJob
	err := scanner.Scan(&job.Sequence, &job.UserID, &job.JobID, &job.RequestDigest,
		&job.SourceID, &job.SourceName, &job.SourceType, &job.ContentSHA256, &job.Plaintext,
		&job.ProcessorID, &job.ProcessorVersion, &job.ModelID, &job.OutputLayer, &job.State,
		&job.CompletedBatches, &job.TotalBatches, &job.AcceptedAt, &job.StartedAt,
		&job.UpdatedAt, &job.CompletedAt, &job.ErrorCode, &job.ErrorMessage, &job.ReceiptJSON)
	return job, err
}

// AcceptSilverRefinementJob atomically accepts the immutable Bronze snapshot
// and gives it to the Node-owned queue. Repeated submissions for the same
// generation observe the existing job instead of creating duplicate work.
func (d *DB) AcceptSilverRefinementJob(
	userID, operationID, requestDigest string,
	source SilverRefinementSource,
	processorID, processorVersion, modelID, outputLayer string,
	totalBatches int,
	now int64,
) (SilverRefinementJob, bool, error) {
	tx, err := d.sql.Begin()
	if err != nil {
		return SilverRefinementJob{}, false, err
	}
	defer tx.Rollback()

	var priorDigest, priorSourceID, priorJobID string
	err = tx.QueryRow(`SELECT request_digest,source_id,COALESCE(job_id,'')
FROM silver_refinement_operations WHERE user_id=? AND operation_id=?`, userID, operationID).
		Scan(&priorDigest, &priorSourceID, &priorJobID)
	if err == nil {
		if priorDigest != requestDigest || priorSourceID != source.SourceID {
			return SilverRefinementJob{}, false, SyncFailure{"idempotency_conflict", "The operation identifier was reused for different Bronze material."}
		}
		if priorJobID != "" {
			job, findErr := findSilverRefinementJobTx(tx, userID, priorJobID)
			if findErr != nil {
				return SilverRefinementJob{}, false, findErr
			}
			return job, false, tx.Commit()
		}
	} else if !errors.Is(err, sql.ErrNoRows) {
		return SilverRefinementJob{}, false, err
	}
	priorOperation := err == nil

	stored, findErr := findSilverRefinementSourceTx(tx, userID, source.SourceID)
	if findErr != nil && !errors.Is(findErr, sql.ErrNoRows) {
		return SilverRefinementJob{}, false, findErr
	}
	// Operations accepted before the durable job schema may be replayed after a
	// newer Bronze generation or a removal. Attach a terminal historical job,
	// but never let that replay roll the current accepted source backward.
	if priorOperation && findErr == nil && (stored.ContentSHA256 != source.ContentSHA256 || stored.RemovedAt != nil) {
		_, err = tx.Exec(`INSERT INTO refinement_jobs(
user_id,job_id,request_digest,source_id,source_name,source_type,content_sha256,plaintext,
processor_id,processor_version,model_id,output_layer,state,total_batches,accepted_at,updated_at,completed_at)
VALUES(?,?,?,?,?,?,?,'',?,?,?,?,'cancelled',?,?,?,?)`, userID, operationID, requestDigest,
			source.SourceID, source.Name, source.SourceType, source.ContentSHA256,
			processorID, processorVersion, modelID, outputLayer, totalBatches, now, now, now)
		if err != nil {
			return SilverRefinementJob{}, false, err
		}
		if _, err = tx.Exec(`UPDATE silver_refinement_operations SET job_id=?
WHERE user_id=? AND operation_id=?`, operationID, userID, operationID); err != nil {
			return SilverRefinementJob{}, false, err
		}
		job, findJobErr := findSilverRefinementJobTx(tx, userID, operationID)
		if findJobErr != nil {
			return SilverRefinementJob{}, false, findJobErr
		}
		return job, false, tx.Commit()
	}
	changed := errors.Is(findErr, sql.ErrNoRows) || stored.ContentSHA256 != source.ContentSHA256 || stored.RemovedAt != nil
	if errors.Is(findErr, sql.ErrNoRows) {
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
		return SilverRefinementJob{}, false, err
	}
	if changed {
		if _, err = tx.Exec(`UPDATE silver_refinement_operations SET completed_at=?
WHERE user_id=? AND source_id=? AND operation_kind='removal' AND completed_at IS NULL`,
			now, userID, source.SourceID); err != nil {
			return SilverRefinementJob{}, false, err
		}
	}

	job, findErr := findReusableSilverRefinementJobTx(tx, userID, source.SourceID,
		source.ContentSHA256, processorID, processorVersion, modelID, outputLayer, !changed)
	if findErr != nil && !errors.Is(findErr, sql.ErrNoRows) {
		return SilverRefinementJob{}, false, findErr
	}
	if errors.Is(findErr, sql.ErrNoRows) {
		jobID := operationID
		_, err = tx.Exec(`INSERT INTO refinement_jobs(
user_id,job_id,request_digest,source_id,source_name,source_type,content_sha256,plaintext,
processor_id,processor_version,model_id,output_layer,state,total_batches,accepted_at,updated_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'queued',?,?,?)`, userID, jobID, requestDigest, source.SourceID,
			source.Name, source.SourceType, source.ContentSHA256, source.Plaintext,
			processorID, processorVersion, modelID, outputLayer, totalBatches, now, now)
		if err != nil {
			return SilverRefinementJob{}, false, err
		}
		job, err = findSilverRefinementJobTx(tx, userID, jobID)
		if err != nil {
			return SilverRefinementJob{}, false, err
		}
	} else if job.State == "failed" {
		_, err = tx.Exec(`UPDATE refinement_jobs SET state='queued',
error_code=NULL,error_message=NULL,completed_at=NULL,updated_at=? WHERE user_id=? AND job_id=?`,
			now, userID, job.JobID)
		if err != nil {
			return SilverRefinementJob{}, false, err
		}
		job, err = findSilverRefinementJobTx(tx, userID, job.JobID)
		if err != nil {
			return SilverRefinementJob{}, false, err
		}
	}

	if priorOperation {
		_, err = tx.Exec(`UPDATE silver_refinement_operations SET job_id=?
WHERE user_id=? AND operation_id=?`, job.JobID, userID, operationID)
	} else {
		_, err = tx.Exec(`INSERT INTO silver_refinement_operations(
user_id,operation_id,request_digest,source_id,operation_kind,job_id)
VALUES(?,?,?,?,'refinement',?)`, userID, operationID, requestDigest, source.SourceID, job.JobID)
	}
	if err != nil {
		return SilverRefinementJob{}, false, err
	}
	return job, changed, tx.Commit()
}

func findReusableSilverRefinementJobTx(tx *sql.Tx, userID, sourceID, contentSHA, processorID, processorVersion, modelID, outputLayer string, includeCompleted bool) (SilverRefinementJob, error) {
	completed := 0
	if includeCompleted {
		completed = 1
	}
	return scanSilverJob(tx.QueryRow(`SELECT `+silverJobColumns+` FROM refinement_jobs
WHERE user_id=? AND source_id=? AND content_sha256=? AND processor_id=? AND processor_version=? AND model_id=? AND output_layer=?
AND state<>'cancelled' AND (state<>'completed' OR ?=1) ORDER BY sequence LIMIT 1`,
		userID, sourceID, contentSHA, processorID, processorVersion, modelID, outputLayer, completed))
}

func findSilverRefinementJobTx(tx *sql.Tx, userID, jobID string) (SilverRefinementJob, error) {
	return scanSilverJob(tx.QueryRow(`SELECT `+silverJobColumns+` FROM refinement_jobs
WHERE user_id=? AND job_id=?`, userID, jobID))
}

func (d *DB) SilverRefinementJob(userID, jobID string) (SilverRefinementJob, error) {
	return scanSilverJob(d.sql.QueryRow(`SELECT `+silverJobColumns+` FROM refinement_jobs
WHERE user_id=? AND job_id=?`, userID, jobID))
}

func (d *DB) ListSilverRefinementJobs(userID string) ([]SilverRefinementJob, error) {
	rows, err := d.sql.Query(`SELECT `+silverJobColumns+` FROM refinement_jobs
WHERE user_id=? ORDER BY sequence`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var jobs []SilverRefinementJob
	for rows.Next() {
		job, scanErr := scanSilverJob(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		jobs = append(jobs, job)
	}
	return jobs, rows.Err()
}

func (d *DB) RecoverSilverRefinementJobs(now int64) error {
	_, err := d.sql.Exec(`UPDATE refinement_jobs SET state='queued',updated_at=? WHERE state='running'`, now)
	return err
}

func (d *DB) ClaimNextSilverRefinementJob(now int64) (*SilverRefinementJob, error) {
	tx, err := d.sql.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	job, err := scanSilverJob(tx.QueryRow(`SELECT ` + silverJobColumns + ` FROM refinement_jobs
WHERE state='queued' ORDER BY sequence LIMIT 1`))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, tx.Commit()
	}
	if err != nil {
		return nil, err
	}
	result, err := tx.Exec(`UPDATE refinement_jobs SET state='running',
started_at=COALESCE(started_at,?),updated_at=? WHERE user_id=? AND job_id=? AND state='queued'`,
		now, now, job.UserID, job.JobID)
	if err != nil {
		return nil, err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return nil, err
	}
	if changed != 1 {
		return nil, errors.New("refinement job was claimed concurrently")
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	job.State, job.UpdatedAt = "running", now
	if job.StartedAt == nil {
		job.StartedAt = &now
	}
	return &job, nil
}

func (d *DB) UpdateSilverRefinementJobProgress(userID, jobID string, completed int, now int64) error {
	_, err := d.sql.Exec(`UPDATE refinement_jobs SET completed_batches=?,updated_at=?
WHERE user_id=? AND job_id=? AND state='running'`, completed, now, userID, jobID)
	return err
}

func (d *DB) CancelSilverRefinementJob(userID, jobID string, now int64) (SilverRefinementJob, error) {
	_, err := d.sql.Exec(`UPDATE refinement_jobs SET state='cancelled',
plaintext='',updated_at=?,completed_at=? WHERE user_id=? AND job_id=? AND state IN ('queued','running','failed')`,
		now, now, userID, jobID)
	if err != nil {
		return SilverRefinementJob{}, err
	}
	return d.SilverRefinementJob(userID, jobID)
}

func (d *DB) RetrySilverRefinementJob(userID, jobID string, now int64) (SilverRefinementJob, error) {
	_, err := d.sql.Exec(`UPDATE refinement_jobs SET state='queued',
error_code=NULL,error_message=NULL,completed_at=NULL,updated_at=?
WHERE user_id=? AND job_id=? AND state='failed'`, now, userID, jobID)
	if err != nil {
		return SilverRefinementJob{}, err
	}
	return d.SilverRefinementJob(userID, jobID)
}

func (d *DB) FailSilverRefinementJob(userID, jobID, code, message string, now int64) error {
	_, err := d.sql.Exec(`UPDATE refinement_jobs SET state='failed',error_code=?,error_message=?,
updated_at=?,completed_at=? WHERE user_id=? AND job_id=? AND state='running'`,
		code, message, now, now, userID, jobID)
	return err
}

func (d *DB) CompleteSilverRefinementJob(userID, jobID, receiptJSON string, now int64) error {
	tx, err := d.sql.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	result, err := tx.Exec(`UPDATE refinement_jobs SET state='completed',
plaintext='',completed_batches=total_batches,updated_at=?,completed_at=?,receipt_json=?,error_code=NULL,error_message=NULL
WHERE user_id=? AND job_id=? AND state='running'`, now, now, nullableString(receiptJSON), userID, jobID)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count == 1 {
		if _, err = tx.Exec(`UPDATE silver_refinement_operations SET completed_at=?
WHERE user_id=? AND job_id=? AND operation_kind='refinement'`, now, userID, jobID); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func nullableString(value string) any {
	if value == "" {
		return nil
	}
	return value
}

func (d *DB) SaveSilverRefinementCheckpoint(
	job SilverRefinementJob, batchIndex int, batchContentSHA, output string, now int64,
) error {
	_, err := d.sql.Exec(`INSERT INTO refinement_checkpoints(
user_id,source_id,content_sha256,processor_id,processor_version,model_id,output_layer,batch_index,batch_content_sha256,output_json,completed_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(user_id,source_id,content_sha256,processor_id,processor_version,model_id,output_layer,batch_index) DO NOTHING`,
		job.UserID, job.SourceID, job.ContentSHA256, job.ProcessorID, job.ProcessorVersion, job.ModelID, job.OutputLayer,
		batchIndex, batchContentSHA, output, now)
	return err
}

func (d *DB) SilverRefinementCheckpoints(job SilverRefinementJob) ([]SilverRefinementCheckpoint, error) {
	rows, err := d.sql.Query(`SELECT batch_index,batch_content_sha256,output_json,completed_at
FROM refinement_checkpoints WHERE user_id=? AND source_id=? AND content_sha256=?
AND processor_id=? AND processor_version=? AND model_id=? AND output_layer=? ORDER BY batch_index`, job.UserID, job.SourceID,
		job.ContentSHA256, job.ProcessorID, job.ProcessorVersion, job.ModelID, job.OutputLayer)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var checkpoints []SilverRefinementCheckpoint
	for rows.Next() {
		var checkpoint SilverRefinementCheckpoint
		if err = rows.Scan(&checkpoint.BatchIndex, &checkpoint.BatchContentSHA,
			&checkpoint.OutputJSON, &checkpoint.CompletedAt); err != nil {
			return nil, err
		}
		checkpoints = append(checkpoints, checkpoint)
	}
	return checkpoints, rows.Err()
}

func (d *DB) MarkCurrentSilverSourceRefined(
	userID, sourceID, contentSHA256, processorVersion, modelID string, now int64,
) error {
	_, err := d.sql.Exec(`UPDATE silver_refinement_sources SET
refined_processor_version=?,refined_model_id=?,refined_at=?
WHERE user_id=? AND source_id=? AND content_sha256=? AND removed_at IS NULL`,
		processorVersion, modelID, now, userID, sourceID, contentSHA256)
	return err
}
