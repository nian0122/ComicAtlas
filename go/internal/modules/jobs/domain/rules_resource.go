package domain

import "encoding/json"

func ValidateResourceTask(request ResourceTask, key string) error {
	validTarget := (request.Type == "image_lq" || request.Type == "hq_release") && (request.TargetType == "comic" || request.TargetType == "chapter") && request.ComicID != ""
	validTarget = validTarget || request.Type == "metadata_batch" && request.TargetType == "batch_preview" && request.ComicID == "" && request.Payload == "{}"
	validTarget = validTarget || request.Type == "command_batch" && (request.TargetType == "image_lq" || request.TargetType == "metadata_refresh" || request.TargetType == "video_transcode" || request.TargetType == "hq_release" || request.TargetType == "comic_trash" || request.TargetType == "chapter_trash" || request.TargetType == "media_trash" || request.TargetType == "trash_restore" || request.TargetType == "trash_purge") && request.ComicID == "" && request.Payload == "{}"
	validTarget = validTarget || request.Type == "import_batch" && request.TargetType == "import_batch" && request.ComicID == "" && request.Payload == "{}"
	validTarget = validTarget || request.Type == "lifecycle" && (request.TargetType == "comic" || request.TargetType == "chapter" || request.TargetType == "media") && request.ComicID != ""
	validTarget = validTarget || request.Type == "lifecycle_audit" && request.TargetType == "trash_entry" && request.ComicID != "" && request.Payload == "{}"
	validTarget = validTarget || request.Type == "metadata_refresh" && request.TargetType == "comic" && request.TargetID == request.ComicID && request.ComicID != "" && request.Payload == "{}"
	validTarget = validTarget || request.Type == "metadata_sync" && request.TargetType == "comic" && request.TargetID == request.ComicID && request.ComicID != "" && request.Payload == "{}"
	validTarget = validTarget || request.Type == "recovery_repair" && request.TargetType == "comic" && request.TargetID == request.ComicID && request.ComicID != ""
	validTarget = validTarget || request.Type == "ai_analysis" && request.TargetType == "comic" && request.TargetID == request.ComicID && request.ComicID != ""
	validTarget = validTarget || request.Type == "media_upload" && request.TargetType == "upload_session" && request.TargetID != "" && request.ComicID != "" && request.Payload == "{}"
	validTarget = validTarget || request.Type == "video_transcode" && (request.TargetType == "comic" || request.TargetType == "chapter" || request.TargetType == "media") && request.ComicID != "" && request.Payload == "{}"
	validTarget = validTarget || request.Type == "archive_export" && request.TargetType == "comic" && request.TargetID == request.ComicID && request.ComicID != ""
	validTarget = validTarget || (request.Type == "storage_refresh" || request.Type == "disk_recovery") && request.TargetType == "storage" && request.TargetID == "media" && request.ComicID == "" && request.Payload == "{}"
	validTarget = validTarget || request.Type == "directory_scan" && request.TargetType == "import_root" && request.ComicID == ""
	validTarget = validTarget || request.Type == "directory_move" && request.TargetType == "directory_preview" && request.ComicID == "" && request.Payload == "{}" && key != ""
	validTarget = validTarget || request.Type == "directory_preflight" && request.TargetType == "directory_preview" && request.ComicID == "" && request.Payload == "{}"
	if !validTarget || request.SourcePath != "" || request.TargetID == "" || len(request.TargetID) > 128 || len(request.ComicID) > 128 || len(request.Title) > 500 || len(key) > 128 || len(request.Payload) > 4096 || !json.Valid([]byte(request.Payload)) {
		return ErrValidation
	}
	var payload map[string]json.RawMessage
	if json.Unmarshal([]byte(request.Payload), &payload) != nil || payload == nil {
		return ErrValidation
	}
	return nil
}
func StartStage(kind string, hasManifest bool) string {
	if kind == "ai_analysis" {
		return "preparing"
	}
	if kind == "directory_move" || kind == "metadata_batch" || kind == "image_lq" || kind == "hq_release" || kind == "video_transcode" || kind == "media_upload" || kind == "lifecycle" {
		return "processing"
	}
	if hasManifest {
		return "publishing"
	}
	return "scanning"
}
