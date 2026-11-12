package domain

const MaxActiveTasks = 32
const MaxAnalysisTasks = 4

func AnalysisQueueFull(active int) bool { return active >= MaxAnalysisTasks }

func QueueFull(active int) bool         { return active >= MaxActiveTasks }
func BatchParentsFull(active int) bool  { return active >= MaxBatchParents }
func BatchChildrenFull(active int) bool { return active >= MaxBatchChildren }

func CancellationState(task Task) string {
	if task.Status == TaskStatusQueued && task.Type != "command_batch" && task.Type != "import_batch" {
		return TaskStatusCancelled
	}
	return TaskStatusCancelling
}
func RecoveredState(task Task) string {
	if task.Status == TaskStatusCancelling {
		if task.Type == "command_batch" || task.Type == "import_batch" {
			return TaskStatusCancelling
		}
		return TaskStatusCancelled
	}
	return TaskStatusQueued
}

func CanRetry(status string) bool { return status == TaskStatusFailed }
func CanCancel(task Task) bool {
	return task.Type != "directory_move" && task.Type != "metadata_sync" && task.Type != "lifecycle" && task.Type != "hq_release" && task.Type != "video_transcode" && task.Type != "media_upload" && (task.Status == TaskStatusQueued || task.Status == TaskStatusRunning) && task.Stage != "publishing"
}

func CancellationReason(task Task) *string {
	if CanCancel(task) {
		return nil
	}
	reason := "TASK_NOT_ACTIVE"
	if task.Status == TaskStatusCancelling {
		reason = "CANCELLATION_REQUESTED"
	}
	if task.Status == TaskStatusQueued || task.Status == TaskStatusRunning {
		if task.Type == "directory_move" {
			reason = "DIRECTORY_MOVE_NOT_CANCELLABLE"
		} else if task.Type == "metadata_sync" {
			reason = "METADATA_SYNC_NOT_CANCELLABLE"
		} else if task.Type == "lifecycle" {
			reason = "LIFECYCLE_NOT_CANCELLABLE"
		} else if task.Type == "hq_release" {
			reason = "HQ_RELEASE_NOT_CANCELLABLE"
		} else if task.Type == "video_transcode" {
			reason = "VIDEO_TRANSCODE_NOT_CANCELLABLE"
		} else if task.Type == "media_upload" {
			reason = "UPLOAD_PUBLISH_NOT_CANCELLABLE"
		} else if task.Stage == "publishing" {
			reason = "PUBLISHING"
		}
	}
	return &reason
}
