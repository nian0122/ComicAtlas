package domain

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"errors"
	"sort"
	"time"
)

func NormalizeRequest(request Request) (Request, error) {
	options, err := NormalizeOptions(BatchOptions{Operation: request.Operation, Patch: request.Patch, LQ: request.LQ})
	if err != nil {
		return request, err
	}
	if UsesTargetSelection(request.Operation) {
		if request.TargetSelection == nil || request.TrashSelection != nil || !emptyComicSelection(request.Selection) {
			return request, librarydomain.ErrValidation
		}
		value, selectionErr := lifecycledomain.NormalizeTargetSelection(TrashTargetType(request.Operation), *request.TargetSelection)
		if selectionErr != nil {
			return request, selectionErr
		}
		request.TargetSelection = &value
	} else if UsesTrashSelection(request.Operation) {
		if request.TargetSelection != nil || request.TrashSelection == nil || !emptyComicSelection(request.Selection) {
			return request, librarydomain.ErrValidation
		}
		value, selectionErr := lifecycledomain.NormalizeEntrySelection(*request.TrashSelection)
		if selectionErr != nil {
			return request, selectionErr
		}
		request.TrashSelection = &value
	} else {
		if request.TrashSelection != nil || request.TargetSelection != nil {
			return request, librarydomain.ErrValidation
		}
		request.Selection, err = librarydomain.NormalizeSelection(request.Selection)
	}
	request.Operation, request.Patch, request.LQ = options.Operation, options.Patch, options.LQ
	return request, err
}
func emptyComicSelection(selection librarydomain.ComicSelection) bool {
	return selection.Mode == "" && len(selection.IDs) == 0 && selection.Filter == nil && len(selection.ExcludedIDs) == 0
}
func NormalizeOptions(request BatchOptions) (BatchOptions, error) {
	if request.Operation != "metadata_update" && !IsCommand(request.Operation) {
		return request, librarydomain.ErrValidation
	}
	var err error
	if request.Operation == "metadata_update" {
		if request.LQ != nil {
			return request, librarydomain.ErrValidation
		}
		if err = librarydomain.ValidateMetadataPatch(request.Patch); err != nil {
			return request, err
		}
	} else {
		if !EmptyPatch(request.Patch) {
			return request, librarydomain.ErrValidation
		}
		if request.Operation == "image_lq" {
			value := mediadomain.LQRequest{}
			if request.LQ != nil {
				value = *request.LQ
			}
			if value.Spec == (mediadomain.ImageSpec{}) {
				value.Spec = mediadomain.DefaultImageSpec()
			}
			if mediadomain.ValidateImageSpec(value.Spec) != nil {
				return request, librarydomain.ErrValidation
			}
			request.LQ = &value
		} else if request.LQ != nil {
			return request, librarydomain.ErrValidation
		}
	}
	request.Patch.AddTagIDs = append([]string(nil), request.Patch.AddTagIDs...)
	sort.Strings(request.Patch.AddTagIDs)
	request.Patch.RemoveTagIDs = append([]string(nil), request.Patch.RemoveTagIDs...)
	sort.Strings(request.Patch.RemoveTagIDs)
	return request, nil
}
func ValidatePreviewCapacity(count int) error {
	if count >= MaxPendingPreviews {
		return jobsdomain.ErrFull
	}
	return nil
}
func ValidateConfirmation(task *string, expires string, eligible int, now time.Time) error {
	at, err := time.Parse(time.RFC3339Nano, expires)
	if err != nil || !now.Before(at) || task != nil || eligible == 0 {
		return jobsdomain.ErrConflict
	}
	return nil
}
func InitialItem(position int, comic librarydomain.SelectedComic, operation string) Item {
	value := Item{Position: position, ComicID: comic.ID, TargetType: "comic", TargetID: comic.ID, Title: comic.Title, Version: comic.Version, Status: "pending", ScopeRevision: comic.ScopeRevision, ImageCount: comic.Images, VideoCount: comic.Videos, HQ: comic.HQ}
	if comic.Version < 1 {
		value.Status = "blocked"
		code := "TARGET_NOT_FOUND"
		value.ErrorCode = &code
	}
	return InitialCommandItem(value, operation)
}
func ItemFailure(err error) (string, bool) {
	switch {
	case errors.Is(err, librarydomain.ErrConflict):
		return "METADATA_VERSION_CHANGED", true
	case errors.Is(err, librarydomain.ErrNotFound):
		return "TARGET_NOT_FOUND", true
	case errors.Is(err, librarydomain.ErrValidation):
		return "METADATA_PATCH_REJECTED", true
	}
	return "", false
}

func AppliedOutcome(item Item, err error) (ItemOutcome, error) {
	if err == nil {
		next := item.Version + 1
		return ItemOutcome{Status: "succeeded", UpdatedVersion: &next}, nil
	}
	code, expected := ItemFailure(err)
	if !expected {
		return ItemOutcome{}, err
	}
	return ItemOutcome{Status: "failed", ErrorCode: &code}, nil
}
func PreviewExpiresAt(operation string, now time.Time) string {
	lifetime := 10 * time.Minute
	if operation == "trash_purge" {
		lifetime = lifecycledomain.ConfirmationLifetime
	}
	return now.Add(lifetime).UTC().Format(jobsdomain.TimestampFormat)
}
func ProjectItem(item Item, taskStatus *string) Item {
	if item.Status == "pending" && taskStatus != nil && *taskStatus == "cancelled" {
		item.Status = "cancelled"
		code := "BATCH_CANCELLED"
		item.ErrorCode = &code
	}
	return item
}

func ProjectPreview(value Preview) Preview {
	if value.Operation == "chapter_trash" {
		value.ConfirmationEffect = "move_chapters_to_trash"
	}
	if value.Operation == "media_trash" {
		value.ConfirmationEffect = "move_media_to_trash"
	}
	if value.Operation == "trash_purge" {
		value.ConfirmationEffect = "delete_trash_and_content"
	}
	if value.Operation == "trash_restore" {
		value.ConfirmationEffect = "restore_trash_entries"
	}
	if value.Operation == "comic_trash" {
		value.ConfirmationEffect = "move_comics_to_trash"
	}
	value.EligibleCount = value.SelectedCount - value.BlockedCount
	if value.TaskStatus != nil && *value.TaskStatus == "cancelled" {
		value.CancelledCount += value.PendingCount
		value.PendingCount = 0
	}
	return value
}
