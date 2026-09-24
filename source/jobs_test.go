package main

import (
	"fmt"
	"path/filepath"
	"testing"
)

func TestSyncJobsTrackPlanCompletionAndRestart(t *testing.T) {
	dir := t.TempDir()
	store, err := newSyncJobStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	plan := []syncJobPlanItem{
		{Key: "first:1:to_source", Title: "first.txt", Direction: "to_source"},
		{Key: "second:1:to_self", Title: "second.jpg", Direction: "to_self"},
	}
	jobs, err := store.plan(plan)
	if err != nil || len(jobs) != 2 {
		t.Fatalf("plan: jobs=%+v err=%v", jobs, err)
	}
	if err := store.complete(jobs[0].JobID); err != nil {
		t.Fatal(err)
	}
	snapshot := store.snapshot()
	if len(snapshot.Queued) != 1 || snapshot.Queued[0].Title != "second.jpg" ||
		len(snapshot.Completed) != 1 || snapshot.Completed[0].CompletedAt <= 0 {
		t.Fatalf("unexpected job snapshot: %+v", snapshot)
	}

	restarted, err := newSyncJobStore(filepath.Dir(store.path))
	if err != nil {
		t.Fatal(err)
	}
	if got := restarted.snapshot(); len(got.Queued) != 1 || len(got.Completed) != 1 {
		t.Fatalf("jobs did not survive restart: %+v", got)
	}

	// An empty current plan proves that the remaining transfer is now in sync.
	if _, err := restarted.plan(nil); err != nil {
		t.Fatal(err)
	}
	if got := restarted.snapshot(); len(got.Queued) != 0 || len(got.Completed) != 2 {
		t.Fatalf("plan reconciliation did not close old work: %+v", got)
	}
}

func TestSyncJobPlanValidation(t *testing.T) {
	store, err := newSyncJobStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	bad := []syncJobPlanItem{{Key: "same", Title: "one", Direction: "to_source"}, {Key: "same", Title: "two", Direction: "to_self"}}
	if _, err := store.plan(bad); err == nil {
		t.Fatal("duplicate plan key accepted")
	}
	if _, err := store.plan([]syncJobPlanItem{{Key: "job", Title: "item", Direction: "sideways"}}); err == nil {
		t.Fatal("invalid direction accepted")
	}
}

func TestJobSnapshotsKeepExistingClientsCompatibleAndStatusBounded(t *testing.T) {
	store, err := newSyncJobStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for index := 0; index < 7; index++ {
		key := fmt.Sprintf("item-%d:1:to_source", index)
		planned, err := store.plan([]syncJobPlanItem{{Key: key, Title: key, Direction: "to_source"}})
		if err != nil {
			t.Fatal(err)
		}
		if err := store.complete(planned[0].JobID); err != nil {
			t.Fatal(err)
		}
	}
	jobs := &sourceJobs{sync: store, silver: &silverService{}}
	snapshot := jobs.snapshot()
	if snapshot.CompletedCount != 7 || len(snapshot.Completed) != 7 {
		t.Fatalf("existing job response lost completed jobs: %+v", snapshot)
	}
	_, status, _ := jobs.refreshStatus()
	if status.CompletedCount != 7 || len(status.Completed) != 5 {
		t.Fatalf("lightweight job status was not bounded: %+v", status)
	}
}
