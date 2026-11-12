package domain

import lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"

func PurgeItems(entries []lifecycledomain.SelectedPurgeEntry) []Item {
	positions := map[string]int{}
	result := make([]Item, 0, len(entries))
	for n, selected := range entries {
		entry := selected.Entry
		item := Item{Position: n + 1, EntryID: entry.ID, TargetType: entry.Target.Type, TargetID: entry.Target.ID, ComicID: entry.Target.ComicID, Title: entry.Target.Title, Status: "pending", PurgePolicyDays: selected.RetentionDays}
		code := selected.Blocker
		if selected.Preview != nil {
			preview := *selected.Preview
			preview.Token = ""
			preview.ExpiresAt = ""
			item.Purge = &preview
			item.EntryStamp = preview.Stamp
		}
		if selected.CoveredByID != "" {
			position, ok := positions[selected.CoveredByID]
			if !ok || result[position-1].Status != "pending" || result[position-1].CoveredBy != nil {
				code = "PURGE_SCOPE_UNAVAILABLE"
			} else {
				item.CoveredBy = &position
			}
		}
		if code != "" {
			item.Status = "blocked"
			item.ErrorCode = &code
		}
		positions[entry.ID] = item.Position
		result = append(result, item)
	}
	return result
}

func SummarizePurge(items []Item) PurgeSummary {
	summary := PurgeSummary{}
	for _, item := range items {
		if summary.RetentionDays == nil {
			days := item.PurgePolicyDays
			summary.RetentionDays = &days
		}
		if item.Status != "pending" {
			continue
		}
		if item.CoveredBy != nil {
			summary.CoveredCount++
			continue
		}
		if item.Purge != nil {
			summary.RootCount++
			summary.EntryCount += item.Purge.EntryCount
			summary.FileCount += item.Purge.FileCount
			summary.RegisteredBytes += item.Purge.RegisteredBytes
			summary.UnmeasuredFiles += item.Purge.UnmeasuredFiles
		}
	}
	return summary
}

func CoveredOutcome(status string) (ItemOutcome, bool) {
	switch status {
	case "succeeded":
		return ItemOutcome{Status: "succeeded"}, true
	case "cancelled":
		code := "BATCH_CANCELLED"
		return ItemOutcome{Status: "cancelled", ErrorCode: &code}, true
	case "blocked", "failed":
		code := "PURGE_PARENT_FAILED"
		return ItemOutcome{Status: "failed", ErrorCode: &code}, true
	}
	return ItemOutcome{}, false
}
