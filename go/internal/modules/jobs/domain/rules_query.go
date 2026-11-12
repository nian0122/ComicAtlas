package domain

import "slices"

var taskTypes = [...]string{
	"directory_import", "archive_import", "image_lq", "storage_refresh", "hq_release",
	"directory_scan", "archive_export", "video_transcode", "media_upload", "metadata_refresh",
	"lifecycle", "lifecycle_audit", "metadata_sync", "metadata_batch", "command_batch",
	"directory_move", "directory_preflight", "disk_recovery", "recovery_repair", "ai_analysis", "import_batch",
}

func TaskTypes() []string { return slices.Clone(taskTypes[:]) }

func ValidateFilter(filter Filter) error {
	if len(filter.Keyword) > 500 {
		return ErrValidation
	}
	if filter.Type != "" && !slices.Contains(taskTypes[:], filter.Type) {
		return ErrValidation
	}
	return nil
}

func ValidateListQuery(query ListQuery) error {
	if err := ValidateFilter(query.Filter); err != nil {
		return err
	}
	if query.Page < 1 || query.Page > 1000000 || query.PageSize < 1 || query.PageSize > 100 {
		return ErrValidation
	}
	switch query.Status {
	case "", TaskStatusQueued, TaskStatusRunning, TaskStatusCancelling, TaskStatusCancelled, TaskStatusSucceeded, TaskStatusFailed:
	default:
		return ErrValidation
	}
	return nil
}
