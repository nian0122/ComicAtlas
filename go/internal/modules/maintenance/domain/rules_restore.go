package domain

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"sort"
)

func RestoreItems(entries []lifecycledomain.SelectedEntry) []Item {
	sort.Slice(entries, func(i, j int) bool {
		a, b := lifecycledomain.EntryLevel(entries[i].Entry.Target.Type), lifecycledomain.EntryLevel(entries[j].Entry.Target.Type)
		if a != b {
			return a < b
		}
		return entries[i].Entry.ID < entries[j].Entry.ID
	})
	positions := map[string]int{}
	for n, entry := range entries {
		positions[entry.Entry.ID] = n + 1
	}
	result := make([]Item, 0, len(entries))
	for n, selected := range entries {
		entry := selected.Entry
		item := Item{Position: n + 1, EntryID: entry.ID, EntryStamp: selected.Stamp, TargetType: entry.Target.Type, TargetID: entry.Target.ID, ComicID: entry.Target.ComicID, Title: entry.Target.Title, Status: "pending"}
		code := selected.Blocker
		if code == "" {
			for _, ancestor := range selected.AncestorIDs {
				position, ok := positions[ancestor]
				if !ok || position >= item.Position {
					code = "RESTORE_ANCESTOR_NOT_SELECTED"
					break
				}
				if result[position-1].Status == "blocked" {
					code = "RESTORE_ANCESTOR_BLOCKED"
					break
				}
				item.Dependencies = append(item.Dependencies, position)
			}
		}
		if code != "" {
			item.Status = "blocked"
			item.ErrorCode = &code
		}
		result = append(result, item)
	}
	return result
}
func ValidateDependencyStates(states []string) error {
	for _, state := range states {
		if state == "pending" || state == "dispatched" {
			return jobsdomain.ErrConflict
		}
		if state != "succeeded" {
			return ErrDependencyFailed
		}
	}
	return nil
}
