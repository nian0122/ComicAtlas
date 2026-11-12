package domain

import "strings"

func ValidateLQScope(kind, id string) error {
	if (kind != "comic" && kind != "chapter") || id == "" || len(id) > 128 {
		return ErrLQValidation
	}
	return nil
}
func ValidateLQSelection(count int) error {
	if count < 1 {
		return ErrLQEmpty
	}
	if count > MaxLQItems {
		return ErrLQValidation
	}
	return nil
}

func ValidateLQResult(status string, hasRecord bool, code string) error {
	if hasRecord && (status == "generated" || status == "skipped") && code == "" {
		return nil
	}
	if !hasRecord && status == "failed" && code != "" {
		return nil
	}
	return ErrLQValidation
}
func CanRecordLQ(status string) bool { return status == "running" || status == "cancelling" }

func OriginalStorageState(sourceSHA, observed string, record *LQRecord) string {
	if observed == "unknown" && sourceSHA == "" && LQMatchesSource(record, sourceSHA) {
		if record.Provenance.OriginalState == "released" {
			return "released"
		}
		return "missing"
	}
	return observed
}
func LQSourceMayBeRecorded(snapshot, current, produced string, generated bool) bool {
	if current != snapshot {
		return false
	}
	if generated && snapshot == "" {
		return lqDigest(produced)
	}
	return produced == current
}
func LQState(mediaID, sourceSHA string, record *LQRecord, taskID, taskStatus, itemStatus, errorCode *string) LQInfo {
	result := LQInfo{State: "missing", TaskID: taskID, TaskStatus: taskStatus, ErrorCode: errorCode}
	if record != nil && record.Valid {
		if LQMatchesSource(record, sourceSHA) {
			url := "/api/v1/media/" + mediaID + "/content?quality=lq"
			result.State, result.Available = "ready", true
			result.Width, result.Height, result.Size, result.ContentURL = &record.Width, &record.Height, &record.Size, &url
			return result
		}
		result.State = "stale"
	}
	if taskStatus != nil {
		switch *taskStatus {
		case "queued":
			result.State = "pending"
		case "running", "cancelling":
			result.State = "processing"
		}
	}
	if itemStatus != nil && *itemStatus == "failed" {
		result.State = "failed"
	}
	return result
}
func ValidLQPath(path string) bool {
	return strings.HasPrefix(path, "lq/") && !strings.Contains(path, "\\") && !strings.Contains(path, "..")
}
