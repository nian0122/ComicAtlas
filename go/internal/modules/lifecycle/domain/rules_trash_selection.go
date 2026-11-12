package domain

import (
	"errors"
	"strings"
)

func NormalizeTargetSelection(kind string, value TargetSelection) (TargetSelection, error) {
	if kind != "chapter" && kind != "media" || len(value.IDs) > MaxSelectedTargets || len(value.ExcludedIDs) > MaxSelectedTargets {
		return value, ErrValidation
	}
	switch value.Mode {
	case "ids":
		if len(value.IDs) == 0 || value.Filter != nil || len(value.ExcludedIDs) != 0 {
			return value, ErrValidation
		}
	case "filter":
		if value.Filter == nil || len(value.IDs) != 0 {
			return value, ErrValidation
		}
		filter := *value.Filter
		if len(filter.Keyword) > 500 || filter.ComicID != "" && !ValidJournalID(filter.ComicID) || filter.ChapterID != "" && !ValidJournalID(filter.ChapterID) {
			return value, ErrValidation
		}
		if filter.Reaction != "" && filter.Reaction != "any" && filter.Reaction != "like" && filter.Reaction != "dislike" {
			return value, ErrValidation
		}
		if filter.MediaType != "" && filter.MediaType != "image" && filter.MediaType != "video" {
			return value, ErrValidation
		}
		if kind == "chapter" && (filter.ChapterID != "" || filter.MediaType != "") {
			return value, ErrValidation
		}
		filter.Keyword = strings.TrimSpace(filter.Keyword)
		value.Filter = &filter
	default:
		return value, ErrValidation
	}
	var err error
	value.IDs, err = normalizeSelectionIDs(value.IDs, MaxSelectedTargets)
	if err != nil {
		return value, err
	}
	value.ExcludedIDs, err = normalizeSelectionIDs(value.ExcludedIDs, MaxSelectedTargets)
	return value, err
}

func ValidateTrashStamp(expected, actual string) error {
	if !validDigest(expected) || expected != actual {
		return ErrScopeChanged
	}
	return nil
}

func ValidTrashStamp(stamp string) bool { return validDigest(stamp) }

func TrashSelectionBlocker(err error) (string, bool) {
	if errors.Is(err, ErrNotFound) {
		return "TARGET_NOT_FOUND", true
	}
	if errors.Is(err, ErrValidation) {
		return "BATCH_TARGET_LIMIT", true
	}
	return "", false
}
