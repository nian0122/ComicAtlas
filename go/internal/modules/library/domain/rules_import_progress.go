package domain

import "time"

// ValidateImportedProgress 保证归档阅读位置锚定到同一导入计划中的活动媒体。
func ValidateImportedProgress(plan Plan) error {
	progress := plan.Progress
	if progress == nil {
		return nil
	}
	if progress.ComicID != plan.Comic.ID || progress.PageNumber < 1 || progress.MediaID == "" || progress.UpdatedAt == "" {
		return ErrInvalidProgress
	}
	if _, err := time.Parse(time.RFC3339Nano, progress.UpdatedAt); err != nil {
		return ErrInvalidProgress
	}
	for _, media := range plan.Media {
		if media.ID == progress.MediaID && media.ChapterID == progress.ChapterID && media.PageNumber == progress.PageNumber {
			for _, chapter := range plan.Chapters {
				if chapter.ID == media.ChapterID {
					return nil
				}
			}
		}
	}
	return ErrInvalidProgress
}
