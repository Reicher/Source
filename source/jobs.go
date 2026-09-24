package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const syncJobSchemaVersion = 1

var errInvalidSyncJobPlan = errors.New("invalid sync job plan")

type sourceJob struct {
	ID          string `json:"id"`
	Key         string `json:"key,omitempty"`
	Kind        string `json:"kind"`
	Title       string `json:"title"`
	Direction   string `json:"direction,omitempty"`
	State       string `json:"state"`
	QueuedAt    int64  `json:"queued_at"`
	CompletedAt int64  `json:"completed_at,omitempty"`
}

type sourceJobSnapshot struct {
	Revision       int64       `json:"revision"`
	QueuedCount    int         `json:"queued_count"`
	CompletedCount int         `json:"completed_count"`
	Queued         []sourceJob `json:"queued"`
	Completed      []sourceJob `json:"completed"`
}

type syncJobPlanItem struct {
	Key       string `json:"key"`
	Title     string `json:"title"`
	Direction string `json:"direction"`
}

type syncJobPlanResult struct {
	Key   string `json:"key"`
	JobID string `json:"job_id"`
}

type syncJobDiskState struct {
	SchemaVersion int         `json:"schema_version"`
	Revision      int64       `json:"revision"`
	NextJob       int64       `json:"next_job"`
	Jobs          []sourceJob `json:"jobs"`
}

type syncJobStore struct {
	mu        sync.Mutex
	path      string
	state     syncJobDiskState
	persisted []byte
}

func newSyncJobStore(dir string) (*syncJobStore, error) {
	s := &syncJobStore{path: filepath.Join(dir, "state.json")}
	value, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		s.state.SchemaVersion = syncJobSchemaVersion
	} else if err != nil {
		return nil, err
	} else if err := json.Unmarshal(value, &s.state); err != nil {
		return nil, fmt.Errorf("invalid sync job state: %w", err)
	}
	if s.state.SchemaVersion != syncJobSchemaVersion {
		return nil, fmt.Errorf("unsupported sync job state version %d", s.state.SchemaVersion)
	}
	for _, job := range s.state.Jobs {
		if job.ID == "" || job.Key == "" || job.Kind != "sync" || job.Title == "" ||
			(job.Direction != "to_source" && job.Direction != "to_self") ||
			(job.State != "queued" && job.State != "completed") || job.QueuedAt <= 0 ||
			(job.State == "completed") != (job.CompletedAt > 0) {
			return nil, errors.New("invalid stored sync job")
		}
	}
	s.persisted, err = json.Marshal(s.state)
	return s, err
}

func validSyncJobPlanItem(item syncJobPlanItem) bool {
	return len(item.Key) > 0 && len(item.Key) <= 256 && !strings.ContainsAny(item.Key, "\r\n") &&
		len(item.Title) > 0 && len(item.Title) <= 512 &&
		(item.Direction == "to_source" || item.Direction == "to_self")
}

// plan records the complete transfer plan. Previously queued work missing from
// the new plan is already synchronized and is closed at the reconciliation time.
func (s *syncJobStore) plan(items []syncJobPlanItem) ([]syncJobPlanResult, error) {
	seen := make(map[string]bool, len(items))
	for _, item := range items {
		if !validSyncJobPlanItem(item) || seen[item.Key] {
			return nil, errInvalidSyncJobPlan
		}
		seen[item.Key] = true
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	before := s.state
	before.Jobs = append([]sourceJob(nil), s.state.Jobs...)
	now := time.Now().UnixMilli()
	changed := false
	active := make(map[string]*sourceJob)
	for index := range s.state.Jobs {
		job := &s.state.Jobs[index]
		if job.State != "queued" {
			continue
		}
		if !seen[job.Key] {
			job.State = "completed"
			job.CompletedAt = now
			changed = true
			continue
		}
		active[job.Key] = job
	}

	results := make([]syncJobPlanResult, 0, len(items))
	for _, item := range items {
		job := active[item.Key]
		if job == nil {
			s.state.NextJob++
			s.state.Jobs = append(s.state.Jobs, sourceJob{
				ID: fmt.Sprintf("sync-%d", s.state.NextJob), Key: item.Key, Kind: "sync",
				Title: item.Title, Direction: item.Direction, State: "queued", QueuedAt: now,
			})
			job = &s.state.Jobs[len(s.state.Jobs)-1]
			active[item.Key] = job
			changed = true
		}
		results = append(results, syncJobPlanResult{Key: item.Key, JobID: job.ID})
	}
	if changed {
		s.state.Revision++
		if err := s.saveLocked(); err != nil {
			s.state = before
			return nil, err
		}
	}
	return results, nil
}

func (s *syncJobStore) complete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	before := s.state
	before.Jobs = append([]sourceJob(nil), s.state.Jobs...)
	for index := range s.state.Jobs {
		job := &s.state.Jobs[index]
		if job.ID != id {
			continue
		}
		if job.State == "completed" {
			return nil
		}
		job.State = "completed"
		job.CompletedAt = time.Now().UnixMilli()
		s.state.Revision++
		if err := s.saveLocked(); err != nil {
			s.state = before
			return err
		}
		return nil
	}
	return os.ErrNotExist
}

func (s *syncJobStore) snapshot() sourceJobSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	snapshot := sourceJobSnapshot{Revision: s.state.Revision, Queued: []sourceJob{}, Completed: []sourceJob{}}
	for _, job := range s.state.Jobs {
		job.Key = ""
		if job.State == "completed" {
			snapshot.Completed = append(snapshot.Completed, job)
		} else {
			snapshot.Queued = append(snapshot.Queued, job)
		}
	}
	return snapshot
}

func (s *syncJobStore) saveLocked() error {
	value, err := json.Marshal(s.state)
	if err != nil {
		return err
	}
	if string(value) == string(s.persisted) {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0700); err != nil {
		return err
	}
	if err := savePrivate(s.path, value); err != nil {
		return err
	}
	s.persisted = value
	return nil
}

type sourceJobs struct {
	sync   *syncJobStore
	silver *silverService
}

func (j *sourceJobs) refreshStatus() (int64, sourceJobSnapshot, []silverProcessing) {
	silverRevision, silverJobs, processing := j.silver.refreshStatus()
	return silverRevision, mergeJobSnapshots(j.sync.snapshot(), silverJobs), processing
}

func (j *sourceJobs) snapshot() sourceJobSnapshot {
	return mergeJobSnapshots(j.sync.snapshot(), j.silver.jobSnapshot())
}

func mergeJobSnapshots(snapshot, silver sourceJobSnapshot) sourceJobSnapshot {
	snapshot.Revision += silver.Revision
	snapshot.Queued = append(snapshot.Queued, silver.Queued...)
	snapshot.Completed = append(snapshot.Completed, silver.Completed...)
	sort.Slice(snapshot.Queued, func(a, b int) bool {
		if snapshot.Queued[a].QueuedAt == snapshot.Queued[b].QueuedAt {
			return snapshot.Queued[a].ID < snapshot.Queued[b].ID
		}
		return snapshot.Queued[a].QueuedAt < snapshot.Queued[b].QueuedAt
	})
	sort.Slice(snapshot.Completed, func(a, b int) bool {
		if snapshot.Completed[a].CompletedAt == snapshot.Completed[b].CompletedAt {
			return snapshot.Completed[a].ID > snapshot.Completed[b].ID
		}
		return snapshot.Completed[a].CompletedAt > snapshot.Completed[b].CompletedAt
	})
	snapshot.QueuedCount = len(snapshot.Queued)
	snapshot.CompletedCount = len(snapshot.Completed)
	if len(snapshot.Completed) > 5 {
		snapshot.Completed = snapshot.Completed[:5]
	}
	return snapshot
}
