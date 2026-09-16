package silver

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"time"

	"source.local/node/internal/apperror"
	"source.local/node/internal/database"
	"source.local/node/internal/syncmodel"
)

type JobError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type Job struct {
	ID                  string                   `json:"id"`
	SourceID            string                   `json:"sourceId"`
	SourceContentSHA256 string                   `json:"sourceContentSha256"`
	ProcessorID         string                   `json:"processorId"`
	ProcessorVersion    string                   `json:"processorVersion"`
	ModelID             string                   `json:"modelId"`
	State               string                   `json:"state"`
	CompletedBatches    int                      `json:"completedBatches"`
	TotalBatches        int                      `json:"totalBatches"`
	AcceptedAtMillis    int64                    `json:"acceptedAtMillis"`
	StartedAtMillis     *int64                   `json:"startedAtMillis,omitempty"`
	UpdatedAtMillis     int64                    `json:"updatedAtMillis"`
	CompletedAtMillis   *int64                   `json:"completedAtMillis,omitempty"`
	Error               *JobError                `json:"error,omitempty"`
	Receipt             *syncmodel.CommitReceipt `json:"receipt,omitempty"`
}

func wireJob(stored database.SilverRefinementJob) Job {
	job := Job{
		ID: stored.JobID, SourceID: stored.SourceID, SourceContentSHA256: stored.ContentSHA256,
		ProcessorID: stored.ProcessorID, ProcessorVersion: stored.ProcessorVersion, ModelID: stored.ModelID, State: stored.State,
		CompletedBatches: stored.CompletedBatches, TotalBatches: stored.TotalBatches,
		AcceptedAtMillis: stored.AcceptedAt, StartedAtMillis: stored.StartedAt,
		UpdatedAtMillis: stored.UpdatedAt, CompletedAtMillis: stored.CompletedAt,
	}
	if stored.ErrorCode != nil && stored.ErrorMessage != nil {
		job.Error = &JobError{Code: *stored.ErrorCode, Message: *stored.ErrorMessage}
	}
	if stored.ReceiptJSON != nil {
		var receipt syncmodel.CommitReceipt
		if json.Unmarshal([]byte(*stored.ReceiptJSON), &receipt) == nil {
			job.Receipt = &receipt
		}
	}
	return job
}

func (s *Service) Close() {
	s.stopWorker()
	<-s.workerDone
}

func (s *Service) signalWorker() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *Service) work() {
	defer close(s.workerDone)
	if err := s.db.RecoverSilverRefinementJobs(s.now().UnixMilli()); err != nil {
		return
	}
	for {
		job, err := s.db.ClaimNextSilverRefinementJob(s.now().UnixMilli())
		if err != nil {
			if !s.waitForRetry() {
				return
			}
			continue
		}
		if job == nil {
			if !s.waitForWork() {
				return
			}
			continue
		}
		s.runJob(*job)
	}
}

func (s *Service) waitForRetry() bool {
	select {
	case <-s.workerCtx.Done():
		return false
	case <-s.wake:
		return true
	case <-time.After(time.Second):
		return true
	}
}

func (s *Service) waitForWork() bool {
	select {
	case <-s.workerCtx.Done():
		return false
	case <-s.wake:
		return true
	}
}

func (s *Service) runJob(job database.SilverRefinementJob) {
	jobCtx, stop := context.WithCancel(s.workerCtx)
	s.runningMu.Lock()
	s.runningUserID, s.runningJobID, s.runningStop = job.UserID, job.JobID, stop
	s.runningMu.Unlock()
	defer func() {
		stop()
		s.runningMu.Lock()
		if s.runningUserID == job.UserID && s.runningJobID == job.JobID {
			s.runningUserID, s.runningJobID, s.runningStop = "", "", nil
		}
		s.runningMu.Unlock()
	}()

	current, err := s.db.SilverRefinementJob(job.UserID, job.JobID)
	if err != nil || current.State != "running" {
		return
	}
	if job.ProcessorID != ExtractionProcessorID || job.ProcessorVersion != ExtractionVersion {
		s.failJob(job, apperror.New(409, "silver_processor_changed", "The refinement processor no longer matches the accepted job."))
		return
	}
	modelID, _ := s.ai.Capabilities()["modelId"].(string)
	if modelID == "" || modelID != job.ModelID {
		s.failJob(job, apperror.New(503, "silver_model_changed", "The refinement model no longer matches the accepted job."))
		return
	}
	chunks := textChunks(job.Plaintext, maximumChunkBytes, chunkOverlapBytes)
	if len(chunks) != job.TotalBatches {
		s.failJob(job, errors.New("immutable refinement input no longer matches its batch plan"))
		return
	}
	checkpoints, err := s.db.SilverRefinementCheckpoints(job)
	if err != nil {
		s.failJob(job, err)
		return
	}
	outputs := make([]string, len(chunks))
	completed := 0
	for _, checkpoint := range checkpoints {
		if checkpoint.BatchIndex < 0 || checkpoint.BatchIndex >= len(chunks) ||
			checkpoint.BatchContentSHA != sha256Text(chunks[checkpoint.BatchIndex]) {
			s.failJob(job, errors.New("durable refinement checkpoint does not match the immutable input snapshot"))
			return
		}
		if _, parseErr := parseExtraction(checkpoint.OutputJSON, chunks[checkpoint.BatchIndex]); parseErr != nil {
			s.failJob(job, errors.New("durable refinement checkpoint is invalid"))
			return
		}
		if outputs[checkpoint.BatchIndex] == "" {
			outputs[checkpoint.BatchIndex] = checkpoint.OutputJSON
			completed++
		}
	}
	if err = s.db.UpdateSilverRefinementJobProgress(job.UserID, job.JobID, completed, s.now().UnixMilli()); err != nil {
		s.failJob(job, err)
		return
	}

	for index, chunk := range chunks {
		if outputs[index] != "" {
			continue
		}
		output, extractErr := extractBatch(jobCtx, s.ai, chunk, job.AuthoredBySelf)
		if extractErr != nil {
			if jobCtx.Err() != nil {
				return
			}
			s.failJob(job, apperror.Wrap(503, "silver_refinement_failed", "The Node could not refine the Bronze source.", extractErr))
			return
		}
		now := s.now().UnixMilli()
		if err = s.db.SaveSilverRefinementCheckpoint(job, index, sha256Text(chunk), output, now); err != nil {
			s.failJob(job, err)
			return
		}
		outputs[index] = output
		completed++
		if err = s.db.UpdateSilverRefinementJobProgress(job.UserID, job.JobID, completed, now); err != nil {
			s.failJob(job, err)
			return
		}
		current, err = s.db.SilverRefinementJob(job.UserID, job.JobID)
		if err != nil || current.State != "running" {
			return
		}
	}

	now := s.now().UnixMilli()
	generation, err := generationFromBatchOutputs(Source{
		ID: job.SourceID, Name: job.SourceName, SourceType: job.SourceType,
		ContentSHA256: job.ContentSHA256, Text: job.Plaintext, AuthoredBySelf: job.AuthoredBySelf,
	}, job.ModelID, now, chunks, outputs)
	if err != nil {
		s.failJob(job, err)
		return
	}

	// Publishing and terminal controls share this lock. A cancel that wins the
	// lock prevents publication; once publication starts it completes atomically.
	s.mu.Lock()
	defer s.mu.Unlock()
	if jobCtx.Err() != nil {
		return
	}
	current, err = s.db.SilverRefinementJob(job.UserID, job.JobID)
	if err != nil || current.State != "running" {
		return
	}
	currentGeneration, err := s.db.IsCurrentSilverRefinementSource(job.UserID, job.SourceID, job.ContentSHA256, job.AuthoredBySelf)
	if err != nil {
		s.failJob(job, err)
		return
	}
	if !currentGeneration {
		s.failJob(job, apperror.New(409, "silver_generation_superseded", "A newer Bronze generation superseded this refinement job."))
		return
	}
	dataset, err := s.currentDataset(job.UserID)
	if err != nil {
		s.failJob(job, err)
		return
	}
	if generationComplete(dataset, job.SourceID, job.ContentSHA256, job.ModelID, job.AuthoredBySelf) {
		_ = s.db.MarkCurrentSilverSourceRefined(job.UserID, job.SourceID, job.ContentSHA256,
			job.ProcessorVersion, job.ModelID, job.AuthoredBySelf, now)
		_ = s.db.CompleteSilverRefinementJob(job.UserID, job.JobID, "", now)
		return
	}
	user, err := s.db.FindUser(job.UserID)
	if err != nil || user == nil {
		if err == nil {
			err = errors.New("Silver profile is unavailable")
		}
		s.failJob(job, err)
		return
	}
	dataset, err = replaceGeneration(dataset, ProfileContext{
		UserID: user.ID, DisplayName: user.DisplayName, SelfEntityID: user.SelfEntityID,
	}, Source{
		ID: job.SourceID, Name: job.SourceName, SourceType: job.SourceType,
		ContentSHA256: job.ContentSHA256, Text: job.Plaintext, AuthoredBySelf: job.AuthoredBySelf,
	}, generation, now)
	if err != nil {
		s.failJob(job, err)
		return
	}
	payload, err := EncodeDataset(dataset)
	if err != nil {
		s.failJob(job, err)
		return
	}
	if err = s.checkFinalQuota(job.UserID, int64(len(payload))); err != nil {
		s.failJob(job, err)
		return
	}
	node, err := s.db.GetNodeState(false)
	if err != nil {
		s.failJob(job, err)
		return
	}
	receipt, _, _, err := s.storage.CommitNodeValue(
		job.UserID, node.NodeID, Collection, canonicalObjectID(Collection), DatasetFormat,
		DatasetFormatVersion, payload, now, s.maximumBytes,
	)
	if err != nil {
		s.failJob(job, err)
		return
	}
	if err = s.db.MarkCurrentSilverSourceRefined(job.UserID, job.SourceID, job.ContentSHA256,
		job.ProcessorVersion, job.ModelID, job.AuthoredBySelf, now); err != nil {
		s.failJob(job, err)
		return
	}
	receiptJSON, _ := json.Marshal(receipt)
	if err = s.db.CompleteSilverRefinementJob(job.UserID, job.JobID, string(receiptJSON), now); err != nil {
		s.failJob(job, err)
	}
}

func sha256Text(value string) string {
	digest := sha256.Sum256([]byte(value))
	return hex.EncodeToString(digest[:])
}

func (s *Service) failJob(job database.SilverRefinementJob, err error) {
	_, code, message := apperror.Details(err)
	_ = s.db.FailSilverRefinementJob(job.UserID, job.JobID, code, message, s.now().UnixMilli())
}

func (s *Service) Job(userID, jobID string) (Job, error) {
	stored, err := s.db.SilverRefinementJob(userID, jobID)
	if errors.Is(err, sql.ErrNoRows) {
		return Job{}, apperror.New(404, "refinement_job_not_found", "The refinement job does not exist.")
	}
	return wireJob(stored), err
}

func (s *Service) Jobs(userID string) ([]Job, error) {
	stored, err := s.db.ListSilverRefinementJobs(userID)
	if err != nil {
		return nil, err
	}
	jobs := make([]Job, len(stored))
	for index := range stored {
		jobs[index] = wireJob(stored[index])
	}
	return jobs, nil
}

func (s *Service) Cancel(userID, jobID string) (Job, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	stored, err := s.db.CancelSilverRefinementJob(userID, jobID, s.now().UnixMilli())
	if errors.Is(err, sql.ErrNoRows) {
		return Job{}, apperror.New(404, "refinement_job_not_found", "The refinement job does not exist.")
	}
	if err != nil {
		return Job{}, err
	}
	s.runningMu.Lock()
	if s.runningUserID == userID && s.runningJobID == jobID && s.runningStop != nil {
		s.runningStop()
	}
	s.runningMu.Unlock()
	return wireJob(stored), nil
}

func (s *Service) Retry(userID, jobID string) (Job, error) {
	stored, err := s.db.RetrySilverRefinementJob(userID, jobID, s.now().UnixMilli())
	if errors.Is(err, sql.ErrNoRows) {
		return Job{}, apperror.New(404, "refinement_job_not_found", "The refinement job does not exist.")
	}
	if err == nil {
		s.signalWorker()
	}
	return wireJob(stored), err
}
