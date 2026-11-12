package domain

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"errors"
	"time"
)

func IsCommand(operation string) bool {
	return operation == "image_lq" || operation == "metadata_refresh" || operation == "video_transcode" || operation == "hq_release" || operation == "comic_trash" || UsesTrashSelection(operation) || UsesTargetSelection(operation)
}
func UsesTargetSelection(operation string) bool {
	return operation == "chapter_trash" || operation == "media_trash"
}
func TrashTargetType(operation string) string {
	switch operation {
	case "comic_trash":
		return "comic"
	case "chapter_trash":
		return "chapter"
	case "media_trash":
		return "media"
	}
	return ""
}
func UsesTrashSelection(operation string) bool {
	return operation == "trash_restore" || operation == "trash_purge"
}
func CommandChildType(operation string) string {
	if TrashTargetType(operation) != "" || UsesTrashSelection(operation) {
		return "lifecycle"
	}
	return operation
}
func ValidateChildOperation(operation, actual string) error {
	if operation == "trash_purge" && actual != "purge" {
		return jobsdomain.ErrConflict
	}
	if TrashTargetType(operation) != "" && actual != "trash" {
		return jobsdomain.ErrConflict
	}
	if operation == "trash_restore" && actual != "restore" {
		return jobsdomain.ErrConflict
	}
	return nil
}
func ValidateChildEntry(operation string, item Item, entry, stamp string) error {
	if UsesTrashSelection(operation) && entry != item.EntryID {
		return ErrScopeChanged
	}
	if operation == "trash_purge" && (item.CoveredBy != nil || item.Purge == nil || item.EntryStamp != stamp) {
		return ErrScopeChanged
	}
	return nil
}
func BatchResource(operation, id string) jobsdomain.ResourceTask {
	value := jobsdomain.ResourceTask{Type: "metadata_batch", TargetType: "batch_preview", TargetID: id, Title: "批量资料更新", Payload: "{}"}
	if IsCommand(operation) {
		value.Type = "command_batch"
		value.TargetType = operation
		value.Title = "批量资源处理"
		if operation == "comic_trash" {
			value.Title = "批量漫画回收"
		}
		if operation == "chapter_trash" {
			value.Title = "批量章节回收"
		}
		if operation == "media_trash" {
			value.Title = "批量媒体回收"
		}
		if operation == "trash_restore" {
			value.Title = "批量回收条目恢复"
		}
		if operation == "trash_purge" {
			value.Title = "批量永久清理"
		}
	}
	return value
}
func MatchesBatchResource(operation, kind, target string) bool {
	expected := BatchResource(operation, "")
	return expected.Type == kind && expected.TargetType == target
}
func EmptyPatch(value librarydomain.MetadataPatch) bool {
	return value.Title == nil && value.Author == nil && value.Description == nil && value.CategoryID == nil && !value.ClearCategory && len(value.AddTagIDs) == 0 && len(value.RemoveTagIDs) == 0
}
func ValidateCommandCapacity(parents, children int) error {
	if jobsdomain.BatchParentsFull(parents) || jobsdomain.BatchChildrenFull(children) {
		return jobsdomain.ErrFull
	}
	return nil
}
func ValidateCommandRevision(actual, expected int64) error {
	if expected < 1 || actual != expected {
		return ErrScopeChanged
	}
	return nil
}
func InitialCommandItem(item Item, operation string) Item {
	if item.Status != "pending" || !IsCommand(operation) {
		return item
	}
	code := ""
	if item.ScopeRevision < 1 {
		code = "BATCH_SCOPE_UNAVAILABLE"
	}
	if operation == "image_lq" {
		if item.ImageCount == 0 {
			code = "NO_IMAGES"
		} else if mediadomain.ValidateLQSelection(item.ImageCount) != nil {
			code = "BATCH_TARGET_LIMIT"
		}
	}
	if operation == "video_transcode" {
		if item.VideoCount == 0 {
			code = "NO_VIDEOS"
		} else if mediadomain.ValidateTranscodeSelection(item.VideoCount, 0, 0) != nil {
			code = "BATCH_TARGET_LIMIT"
		}
	}
	if operation == "hq_release" {
		if item.HQ == nil {
			code = "BATCH_SCOPE_UNAVAILABLE"
		} else if !item.HQ.Allowed && len(item.HQ.Blockers) > 0 {
			code = item.HQ.Blockers[0]
		}
	}
	if code != "" {
		item.Status = "blocked"
		item.ErrorCode = &code
	}
	return item
}
func ChildOutcome(status string, code *string) (ItemOutcome, bool) {
	switch status {
	case "succeeded":
		return ItemOutcome{Status: "succeeded"}, true
	case "cancelled":
		fixed := "BATCH_CHILD_CANCELLED"
		return ItemOutcome{Status: "cancelled", ErrorCode: &fixed}, true
	case "failed":
		task := jobsdomain.WithDiagnostics(jobsdomain.Task{Status: "failed", ErrorCode: code})
		return ItemOutcome{Status: "failed", ErrorCode: task.ErrorCode}, true
	}
	return ItemOutcome{}, false
}
func CommandTerminal(status string, progress CommandProgress) string {
	if progress.Pending+progress.Active != 0 {
		return ""
	}
	if status == "cancelling" {
		return "cancelled"
	}
	return "succeeded"
}

func FailedCommandTask(task jobsdomain.Task, stamp string) jobsdomain.Task {
	if task.Status == "queued" {
		task.Attempt++
		task.StartedAt = &stamp
	}
	task.Status = "failed"
	task.Stage = "coordinating"
	task.FinishedAt = &stamp
	code := jobsdomain.FailureCommandBatch
	task.ErrorCode = &code
	return task
}
func DispatchFailure(err error) (string, bool) {
	switch {
	case errors.Is(err, lifecycledomain.ErrJournal):
		return "PURGE_MANIFEST_INVALID", true
	case errors.Is(err, ErrScopeChanged), errors.Is(err, lifecycledomain.ErrScopeChanged):
		return "BATCH_SCOPE_CHANGED", true
	case errors.Is(err, ErrDependencyFailed):
		return "BATCH_DEPENDENCY_FAILED", true
	case errors.Is(err, librarydomain.ErrNotFound), errors.Is(err, mediadomain.ErrLQNotFound), errors.Is(err, storagedomain.ErrNotFound), errors.Is(err, lifecycledomain.ErrNotFound):
		return "TARGET_NOT_FOUND", true
	case errors.Is(err, mediadomain.ErrLQEmpty):
		return "NO_IMAGES", true
	case errors.Is(err, mediadomain.ErrLQValidation), errors.Is(err, mediadomain.ErrTranscodeLimit), errors.Is(err, librarydomain.ErrValidation), errors.Is(err, storagedomain.ErrValidation), errors.Is(err, lifecycledomain.ErrValidation):
		return "BATCH_TARGET_LIMIT", true
	}
	return "", false
}
func DispatchWait(err error) bool {
	return errors.Is(err, jobsdomain.ErrFull) || errors.Is(err, jobsdomain.ErrConflict) || errors.Is(err, mediadomain.ErrLQConflict) || errors.Is(err, storagedomain.ErrConflict) || errors.Is(err, lifecycledomain.ErrConflict)
}
func DispatchUpdateFor(code string, final bool, now time.Time) DispatchUpdate {
	if final {
		return DispatchUpdate{Status: "failed", ErrorCode: &code}
	}
	return DispatchUpdate{Status: "pending", WaitReason: &code, NextTryAt: now.Add(2 * time.Second).UTC().Format(jobsdomain.TimestampFormat)}
}
