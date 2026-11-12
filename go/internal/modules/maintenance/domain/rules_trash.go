package domain

import lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"

func TrashItems(targets []lifecycledomain.SelectedTarget) []Item {
	items := make([]Item, 0, len(targets))
	for n, selected := range targets {
		target := selected.Target
		item := Item{Position: n + 1, TargetType: target.Type, TargetID: target.ID, ComicID: target.ComicID, ChapterID: target.ChapterID, Title: target.Title, TargetStamp: selected.Stamp, Status: "pending"}
		if selected.Blocker != "" {
			code := selected.Blocker
			item.Status, item.ErrorCode = "blocked", &code
		} else {
			files := selected.FileCount
			item.TargetFiles = &files
		}
		items = append(items, item)
	}
	return items
}

func ValidateTrashDependency(failedLock bool) error {
	if failedLock {
		return ErrDependencyFailed
	}
	return nil
}
