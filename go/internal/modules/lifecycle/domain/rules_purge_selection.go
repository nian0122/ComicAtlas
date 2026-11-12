package domain

import (
	"errors"
	"sort"
)

func SortPurgeSelection(entries []SelectedEntry) {
	sort.Slice(entries, func(i, j int) bool {
		a, b := EntryLevel(entries[i].Entry.Target.Type), EntryLevel(entries[j].Entry.Target.Type)
		if a != b {
			return a < b
		}
		return entries[i].Entry.ID < entries[j].Entry.ID
	})
}
func PurgeSelectionBlocker(err error) (string, bool) {
	switch {
	case errors.Is(err, ErrNotFound):
		return "TARGET_NOT_FOUND", true
	case errors.Is(err, ErrConflict):
		return "PURGE_SCOPE_UNAVAILABLE", true
	case errors.Is(err, ErrValidation):
		return "PURGE_SCOPE_LIMIT", true
	case errors.Is(err, ErrJournal):
		return "PURGE_MANIFEST_INVALID", true
	}
	return "", false
}
func purgeScopeExcludes(scope PurgeScope, excluded map[string]bool) bool {
	for _, item := range scope.Items {
		if excluded[item.Entry.ID] {
			return true
		}
	}
	return false
}
func ValidatePurgeSnapshot(expected string, preview PurgePreview) error {
	if !validDigest(expected) || !preview.Allowed || expected != preview.Stamp {
		return ErrScopeChanged
	}
	return nil
}

func NewPurgeSelection(entries []SelectedEntry, excluded []string) *PurgeSelectionState {
	state := &PurgeSelectionState{selected: map[string]bool{}, excluded: map[string]bool{}, owners: map[string]string{}}
	for _, entry := range entries {
		state.selected[entry.Entry.ID] = true
	}
	for _, id := range excluded {
		state.excluded[id] = true
	}
	return state
}

// Next 返回是否需要读取实际范围；覆盖行及不适用条目不加载文件清单。
func (state *PurgeSelectionState) Next(entry Entry, days int) (SelectedPurgeEntry, bool) {
	value := SelectedPurgeEntry{Entry: entry, RetentionDays: days}
	if owner := state.owners[entry.ID]; owner != "" {
		value.CoveredByID = owner
		return value, false
	}
	if entry.State == "" {
		value.Blocker = "TARGET_NOT_FOUND"
		return value, false
	}
	if entry.State != "trashed" {
		value.Blocker = "ENTRY_NOT_TRASHED"
		return value, false
	}
	return value, true
}

func (state *PurgeSelectionState) Preview(value SelectedPurgeEntry, scope PurgeScope, preview PurgePreview) SelectedPurgeEntry {
	value.Preview = &preview
	if purgeScopeExcludes(scope, state.excluded) {
		value.Blocker = "PURGE_EXCLUDED_DESCENDANT"
	} else if !preview.Allowed {
		value.Blocker = "RETENTION_ACTIVE"
	}
	return value
}

// Resolve 在范围及忙碌检查均通过后才登记所有权，阻塞父范围不会吞掉子选项。
func (state *PurgeSelectionState) Resolve(value SelectedPurgeEntry, scope PurgeScope, busy bool) SelectedPurgeEntry {
	if value.Blocker != "" {
		return value
	}
	if busy {
		value.Blocker = "TARGET_BUSY"
		return value
	}
	for _, item := range scope.Items {
		if item.Entry.ID != scope.Root.ID && state.selected[item.Entry.ID] {
			state.owners[item.Entry.ID] = scope.Root.ID
		}
	}
	return value
}
