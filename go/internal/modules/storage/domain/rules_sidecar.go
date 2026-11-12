package domain

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"time"
)

func ValidateSidecarCapacity(count int) error {
	if count >= MaxSidecarTasks {
		return jobsdomain.ErrFull
	}
	return nil
}

func SidecarRetryAt(state SidecarState) *string {
	if state.TaskStatus != "failed" || state.FinishedAt == nil {
		return nil
	}
	finished, err := time.Parse(time.RFC3339Nano, *state.FinishedAt)
	if err != nil {
		return nil
	}
	delay := 5 * time.Second
	for attempt := 1; attempt < state.Attempt && delay < 5*time.Minute; attempt++ {
		delay = min(delay*2, 5*time.Minute)
	}
	value := finished.Add(delay).UTC().Format(jobsdomain.TimestampFormat)
	return &value
}

func SidecarAction(state SidecarState, now time.Time) string {
	if state.Revision <= state.PublishedRevision || state.Blocked || state.TaskStatus == "queued" || state.TaskStatus == "running" || state.TaskStatus == "cancelling" {
		return "wait"
	}
	changed, err := time.Parse(time.RFC3339Nano, state.RequestedAt)
	if err != nil || now.Before(changed.Add(time.Second)) {
		return "wait"
	}
	if state.TaskStatus == "failed" {
		retry := SidecarRetryAt(state)
		if retry == nil {
			return "wait"
		}
		at, _ := time.Parse(time.RFC3339Nano, *retry)
		if now.Before(at) {
			return "wait"
		}
		return "retry"
	}
	return "create"
}

func SidecarView(state SidecarState) SidecarStatus {
	value := SidecarStatus{ComicID: state.ComicID, Revision: state.Revision, PublishedRevision: state.PublishedRevision, PublishedAt: state.PublishedAt, TaskID: state.TaskID, State: "pending"}
	if state.Revision == state.PublishedRevision {
		value.State = "synced"
		return value
	}
	if state.TaskStatus == "queued" || state.TaskStatus == "running" || state.TaskStatus == "cancelling" {
		value.State = "syncing"
		return value
	}
	if state.Blocked {
		value.State = "blocked"
		return value
	}
	if state.TaskStatus == "failed" {
		value.State = "failed"
		value.RetryAt = SidecarRetryAt(state)
		task := jobsdomain.WithDiagnostics(jobsdomain.Task{Status: "failed", ErrorCode: state.ErrorCode})
		value.ErrorCode = task.ErrorCode
		value.Failure = task.Failure
	}
	return value
}

func AutomaticSidecarData(task string, snapshot librarydomain.SidecarSnapshot, observed string) ([]byte, string, error) {
	if task == "" || len(task) > 128 || snapshot.ComicID == "" || snapshot.Revision < 1 || len(snapshot.Data) > librarydomain.MaxSnapshotBytes || !json.Valid(snapshot.Data) {
		return nil, "", ErrValidation
	}
	if _, err := time.Parse(time.RFC3339Nano, observed); err != nil {
		return nil, "", ErrValidation
	}
	baseline := sha256.Sum256(snapshot.Data)
	if hex.EncodeToString(baseline[:]) != snapshot.SHA256 {
		return nil, "", ErrValidation
	}
	data, err := json.Marshal(AutomaticMetadataSidecar{Version: 1, TaskID: task, Producer: "automatic", SyncRevision: snapshot.Revision, ObservedAt: observed, BaselineSHA256: snapshot.SHA256, Snapshot: snapshot.Data})
	if err != nil {
		return nil, "", err
	}
	if len(data) > 2*librarydomain.MaxSnapshotBytes {
		return nil, "", ErrScanLimit
	}
	digest := sha256.Sum256(data)
	return data, hex.EncodeToString(digest[:]), nil
}
