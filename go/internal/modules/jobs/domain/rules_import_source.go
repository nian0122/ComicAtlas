package domain

import "strings"

func ImportSourcesOverlap(first, second string) bool {
	return first == second || strings.HasPrefix(first, second+"/") || strings.HasPrefix(second, first+"/")
}
func CanRetryTask(task Task) bool {
	if task.Type == "directory_preflight" {
		return false // 旧预检已合并到单任务，只保留历史。
	}
	if task.Type == "lifecycle" && (task.Stage == StagePurgePreflightFailed || task.Stage == StageTrashPreparationFailed) {
		return false
	}
	return CanRetry(task.Status) || task.Status == TaskStatusCancelled && (task.Type == "directory_import" || task.Type == "archive_import") && ImportHasMoved(task.Stage)
}

const (
	StagePurgePreflightFailed   = "purge_preflight_failed"
	StageTrashPreparationFailed = "trash_preparation_failed"
)

func ImportHasMoved(stage string) bool {
	return stage == "normalizing" || stage == "analyzing" || stage == "publishing"
}
