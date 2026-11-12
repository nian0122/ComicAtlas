package domain

import "strings"

func UploadSubmissionAllowed(session UploadSession, now string) error {
	if session.TaskID != nil {
		return nil
	}
	if err := UploadActive(session, now); err != nil {
		return err
	}
	if len(session.Files) == 0 {
		return ErrUploadConflict
	}
	for _, file := range session.Files {
		if file.ReceivedBytes != file.Size || len(file.ReceivedParts) != int((file.Size+UploadChunkBytes-1)/UploadChunkBytes) {
			return ErrUploadConflict
		}
	}
	return nil
}
func UploadPublishedID(file UploadFile, replace *string) string {
	if replace != nil {
		return *replace
	}
	return "upload_media_" + strings.TrimPrefix(file.ID, "uf_")
}
func UploadTargetPath(file UploadPublishFile) string {
	if file.Info == nil || !StorageKey(file.ComicID) || !StorageKey(file.ChapterID) || !UploadID(file.FileID, "uf_") {
		return ""
	}
	return "hq/" + file.ComicID + "/" + file.ChapterID + "/" + file.FileID + InspectionExtension(*file.Info)
}
func UploadPreviousMatches(expected *UploadPrevious, current UploadPrevious) bool {
	return expected != nil && *expected == current
}
func UploadMayAbandon(status, taskStatus string) bool {
	return status == "submitted" && taskStatus == "failed"
}

func UploadRetirePlan(prior UploadPrior) []UploadRetired {
	items := []UploadRetired{}
	if prior.Previous.State == "available" {
		items = append(items, UploadRetired{Kind: "hq", Path: prior.Previous.Path, SHA256: prior.Previous.SHA256, Size: prior.Previous.Size})
	}
	if prior.LQ != nil {
		items = append(items, UploadRetired{Kind: "lq", Path: prior.LQ.Path, SHA256: prior.LQ.SHA256, Size: prior.LQ.Size})
	}
	return items
}
func UploadIsUnfinished(status string) bool { return status == "submitted" || status == "activated" }
func UploadCleanupPending(status string, cleaned bool) bool {
	return !cleaned && (status == "cancelled" || status == "expired")
}
func UploadAppendBase(current, count, maxPage int) (int, error) {
	if current < 0 || count < 1 || current+count > MaxUploadFiles || maxPage < current {
		return 0, ErrUploadConflict
	}
	return maxPage, nil
}
