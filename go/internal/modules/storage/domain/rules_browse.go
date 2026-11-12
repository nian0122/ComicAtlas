package domain

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

func ValidateBrowse(kind, id string, query BrowseQuery) error {
	if (kind != "comic" || id != "") && (kind != "chapter" || id == "" || len(id) > 128) {
		return ErrValidation
	}
	if query.Page < 1 || query.Page > 1000000 || query.PageSize < 1 || query.PageSize > 100 || len(query.Keyword) > 500 || len(query.CategoryID) > 128 || (query.Uncategorized && query.CategoryID != "") || len(query.TagIDs) > 50 {
		return ErrValidation
	}
	for _, tag := range query.TagIDs {
		if tag == "" || len(tag) > 128 {
			return ErrValidation
		}
	}
	switch query.TagMode {
	case "":
		if len(query.TagIDs) > 0 {
			return ErrValidation
		}
	case "any", "all", "not":
		if len(query.TagIDs) == 0 {
			return ErrValidation
		}
	case "none":
		if len(query.TagIDs) > 0 {
			return ErrValidation
		}
	default:
		return ErrValidation
	}
	switch query.HQStatus {
	case "", "has_hq", "no_hq":
	default:
		return ErrValidation
	}
	switch query.LQStatus {
	case "", "needs_lq", "ready":
	default:
		return ErrValidation
	}
	switch query.Attention {
	case "":
	case "missing_lq", "can_release", "missing_source_sha256":
		if kind != "comic" {
			return ErrValidation
		}
	default:
		return ErrValidation
	}
	switch query.Sort {
	case "", "title", "createdAt", "pages", "totalBytes", "hqBytes", "lqBytes", "globalOrder":
	default:
		return ErrValidation
	}
	if query.Sort == "globalOrder" && kind != "chapter" {
		return ErrValidation
	}
	if (query.Sort == "createdAt" || query.Sort == "pages") && kind != "comic" {
		return ErrValidation
	}
	switch query.Order {
	case "", "asc", "desc":
	default:
		return ErrValidation
	}
	return nil
}

func DescribeStorage(object StorageObject) StorageObject {
	object.TotalBytes = object.Inventory.HQ.Bytes + object.Inventory.LQ.Bytes + object.Inventory.Covers.Bytes
	object.HQStatus = "empty"
	count := object.Inventory.Images + object.Inventory.Videos
	if count > 0 {
		switch {
		case object.Facts.Images.Releasing > 0:
			object.HQStatus = "releasing"
		case object.Inventory.HQ.Files == count:
			object.HQStatus = "available"
		case object.Inventory.HQ.Files == 0:
			object.HQStatus = "released"
		default:
			object.HQStatus = "mixed"
		}
	}
	object.LQStatus = "empty"
	if object.Inventory.Images > 0 {
		switch {
		case object.Inventory.CurrentLQImages == object.Inventory.Images:
			object.LQStatus = "ready"
		case object.Inventory.CurrentLQImages == 0:
			object.LQStatus = "missing"
		default:
			object.LQStatus = "partial"
		}
	}
	facts := object.Facts
	object.ActiveTasks = []TaskActivity{}
	for _, task := range facts.Tasks {
		object.ActiveTasks = append(object.ActiveTasks, TaskActivity{ID: task.ID, Type: task.Type, TargetType: task.TargetType, TargetID: task.TargetID, Status: task.Status, CanCancel: jobsdomain.CanCancel(task), CancellationReason: jobsdomain.CancellationReason(task)})
	}
	lq := Operation{Blockers: []string{}}
	if facts.ActiveMetadata > 0 {
		lq.Blockers = append(lq.Blockers, "METADATA_REFRESH_ACTIVE")
	}
	if facts.IncompleteMetadata > 0 {
		lq.Blockers = append(lq.Blockers, "INCOMPLETE_METADATA_REFRESH")
	}
	if facts.ActiveUpload > 0 {
		lq.Blockers = append(lq.Blockers, "UPLOAD_TASK_ACTIVE")
	}
	if facts.IncompleteUpload > 0 {
		lq.Blockers = append(lq.Blockers, "INCOMPLETE_UPLOAD")
	}
	if facts.ActiveTranscode > 0 {
		lq.Blockers = append(lq.Blockers, "TRANSCODE_TASK_ACTIVE")
	}
	if facts.IncompleteTranscode > 0 {
		lq.Blockers = append(lq.Blockers, "INCOMPLETE_TRANSCODE")
	}
	if facts.Images.Images == 0 {
		lq.Blockers = append(lq.Blockers, "NO_IMAGES")
	}
	if facts.Images.Images > mediadomain.MaxLQItems {
		lq.Blockers = append(lq.Blockers, "TOO_MANY_IMAGES")
	}
	if facts.ActiveLQ > 0 {
		lq.Blockers = append(lq.Blockers, "LQ_TASK_ACTIVE")
	}
	if facts.ActiveExport > 0 {
		lq.Blockers = append(lq.Blockers, "EXPORT_TASK_ACTIVE")
	}
	if facts.ActiveHQ > 0 {
		lq.Blockers = append(lq.Blockers, "HQ_TASK_ACTIVE")
	}
	if facts.IncompleteRelease > 0 {
		lq.Blockers = append(lq.Blockers, "INCOMPLETE_RELEASE")
	}
	preview := mediadomain.HQReleasePreview{Blockers: []string{}}
	mediadomain.AddHQCounts(&preview, facts.Images)
	mediadomain.FinishHQPreview(&preview, facts.ActiveMedia > 0, facts.IncompleteRelease > 0)
	hq := Operation{Blockers: preview.Blockers}
	if facts.IncompleteMetadata > 0 {
		hq.Blockers = append(hq.Blockers, "INCOMPLETE_METADATA_REFRESH")
	}
	if facts.IncompleteUpload > 0 {
		hq.Blockers = append(hq.Blockers, "INCOMPLETE_UPLOAD")
	}
	if facts.IncompleteTranscode > 0 {
		hq.Blockers = append(hq.Blockers, "INCOMPLETE_TRANSCODE")
	}
	transcode := Operation{Blockers: []string{}}
	if facts.IncompleteMetadata > 0 {
		transcode.Blockers = append(transcode.Blockers, "INCOMPLETE_METADATA_REFRESH")
	}
	if facts.IncompleteUpload > 0 {
		transcode.Blockers = append(transcode.Blockers, "INCOMPLETE_UPLOAD")
	}
	if object.Inventory.Videos > mediadomain.MaxTranscodeItems {
		transcode.Blockers = append(transcode.Blockers, "TOO_MANY_VIDEOS")
	}
	if facts.ActiveMedia > 0 {
		transcode.Blockers = append(transcode.Blockers, "MEDIA_TASK_ACTIVE")
	}
	if facts.IncompleteRelease > 0 {
		transcode.Blockers = append(transcode.Blockers, "INCOMPLETE_RELEASE")
	}
	if facts.IncompleteTranscode > 0 {
		transcode.Blockers = append(transcode.Blockers, "INCOMPLETE_TRANSCODE")
	}
	if jobsdomain.QueueFull(facts.ActiveTasks) {
		lq.Blockers = append(lq.Blockers, "QUEUE_FULL")
		hq.Blockers = append(hq.Blockers, "QUEUE_FULL")
		transcode.Blockers = append(transcode.Blockers, "QUEUE_FULL")
	}
	lq.CanSubmit, hq.CanSubmit = len(lq.Blockers) == 0, len(hq.Blockers) == 0
	transcode.CanSubmit = len(transcode.Blockers) == 0
	refresh := Operation{Blockers: []string{}}
	if object.Inventory.ScopeType != "comic" {
		refresh.Blockers = append(refresh.Blockers, "COMIC_SCOPE_REQUIRED")
	}
	if facts.ActiveMedia > 0 {
		refresh.Blockers = append(refresh.Blockers, "MEDIA_TASK_ACTIVE")
	}
	if facts.IncompleteRelease > 0 {
		refresh.Blockers = append(refresh.Blockers, "INCOMPLETE_RELEASE")
	}
	if facts.IncompleteTranscode > 0 {
		refresh.Blockers = append(refresh.Blockers, "INCOMPLETE_TRANSCODE")
	}
	if facts.IncompleteUpload > 0 {
		refresh.Blockers = append(refresh.Blockers, "INCOMPLETE_UPLOAD")
	}
	if facts.IncompleteMetadata > 0 {
		refresh.Blockers = append(refresh.Blockers, "INCOMPLETE_METADATA_REFRESH")
	}
	if jobsdomain.QueueFull(facts.ActiveTasks) {
		refresh.Blockers = append(refresh.Blockers, "QUEUE_FULL")
	}
	if object.Inventory.Images+object.Inventory.Videos > librarydomain.MaxSnapshotMedia {
		refresh.Blockers = append(refresh.Blockers, "TOO_MANY_MEDIA")
	}
	refresh.CanSubmit = len(refresh.Blockers) == 0
	object.Operations = Operations{GenerateLQ: lq, ReleaseHQ: hq, Transcode: transcode, RefreshMetadata: refresh}
	return object
}
