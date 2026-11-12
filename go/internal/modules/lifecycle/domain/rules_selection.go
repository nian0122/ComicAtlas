package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sort"
	"strings"
)

func NormalizeEntrySelection(value EntrySelection) (EntrySelection, error) {
	if value.Mode == "ids" {
		if len(value.IDs) == 0 || value.Filter != nil || len(value.ExcludedIDs) != 0 {
			return value, ErrValidation
		}
	} else if value.Mode == "filter" {
		if value.Filter == nil || len(value.IDs) != 0 || len(value.Filter.Keyword) > 500 {
			return value, ErrValidation
		}
		if value.Filter.TargetType != "" && value.Filter.TargetType != "comic" && value.Filter.TargetType != "chapter" && value.Filter.TargetType != "media" {
			return value, ErrValidation
		}
		copy := *value.Filter
		copy.Keyword = strings.TrimSpace(copy.Keyword)
		value.Filter = &copy
	} else {
		return value, ErrValidation
	}
	var err error
	if value.IDs, err = normalizeSelectionIDs(value.IDs, MaxSelectedEntries); err != nil {
		return value, err
	}
	value.ExcludedIDs, err = normalizeSelectionIDs(value.ExcludedIDs, MaxSelectedEntries)
	return value, err
}

func normalizeSelectionIDs(ids []string, maximum int) ([]string, error) {
	if len(ids) > maximum {
		return nil, ErrValidation
	}
	result := append([]string(nil), ids...)
	sort.Strings(result)
	out := result[:0]
	for _, id := range result {
		if !ValidJournalID(id) {
			return nil, ErrValidation
		}
		if len(out) == 0 || out[len(out)-1] != id {
			out = append(out, id)
		}
	}
	return out, nil
}

// EntryStamp 只覆盖本条回收记录及目标自身状态；祖先恢复造成的顺序变化不更换此条归属。
func EntryStamp(entry Entry, plan, targetState string) string {
	planHash := sha256.Sum256([]byte(plan))
	data, _ := json.Marshal(struct {
		ID, Kind, Target, Comic, Chapter, State, Updated, Task, TargetState string
		PlanHash                                                            [32]byte
	}{
		entry.ID, entry.Target.Type, entry.Target.ID, entry.Target.ComicID, entry.Target.ChapterID, entry.State, entry.UpdatedAt, entry.TaskID, targetState, planHash,
	})
	digest := sha256.Sum256(data)
	return hex.EncodeToString(digest[:])
}
func SelectRestoreEntry(entry Entry, plan, own, comic, chapter, comicEntry, chapterEntry string) SelectedEntry {
	value := SelectedEntry{Entry: entry, Stamp: EntryStamp(entry, plan, own)}
	if entry.State == "" {
		value.Blocker = "TARGET_NOT_FOUND"
		return value
	}
	if entry.State != "trashed" {
		value.Blocker = "ENTRY_NOT_TRASHED"
		return value
	}
	if own != "trashed" {
		value.Blocker = "RESTORE_TARGET_CHANGED"
		return value
	}
	if entry.Target.Type != "comic" && comic != "active" {
		if comic == "trashed" && comicEntry != "" {
			value.AncestorIDs = append(value.AncestorIDs, comicEntry)
		} else {
			value.Blocker = "RESTORE_ANCESTOR_BUSY"
		}
	}
	if entry.Target.Type == "media" && chapter != "active" {
		if chapter == "trashed" && chapterEntry != "" {
			value.AncestorIDs = append(value.AncestorIDs, chapterEntry)
		} else {
			value.Blocker = "RESTORE_ANCESTOR_BUSY"
		}
	}
	return value
}
func EntryLevel(kind string) int {
	switch kind {
	case "comic":
		return 0
	case "chapter":
		return 1
	case "media":
		return 2
	}
	return 3
}
func ValidateRestoreStamp(expected, actual string, ancestors bool) error {
	if expected != "" && (expected != actual || !ancestors) {
		return ErrScopeChanged
	}
	return nil
}
func ValidRestoreStamp(stamp string) bool {
	data, err := hex.DecodeString(stamp)
	return err == nil && len(data) == 32 && hex.EncodeToString(data) == stamp
}
func ValidateRestoreIdentity(expected, current Target) error {
	if expected.Type != current.Type || expected.ID != current.ID || expected.ComicID != current.ComicID || expected.ChapterID != current.ChapterID {
		return ErrScopeChanged
	}
	return nil
}
