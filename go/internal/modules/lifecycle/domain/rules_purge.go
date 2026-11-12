package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sort"
	"time"
)

func ConfirmationHash(token string) (string, error) {
	if !validDigest(token) {
		return "", ErrConfirmation
	}
	digest := sha256.Sum256([]byte(token))
	return hex.EncodeToString(digest[:]), nil
}

func EvaluatePurge(scope PurgeScope, days int, now time.Time) (PurgePreview, error) {
	value := PurgePreview{EntryID: scope.Root.ID, RetentionDays: days, Blockers: []string{}}
	if days < 0 || days > 3650 || len(scope.Items) == 0 || len(scope.Items) > MaxFiles || scope.Root.State != "trashed" || ValidateTarget(scope.Root.Target.Type, scope.Root.Target.ID) != nil {
		return value, ErrConflict
	}
	items := append([]PurgeItem{}, scope.Items...)
	sort.Slice(items, func(i, j int) bool { return items[i].Entry.ID < items[j].Entry.ID })
	hash := sha256.New()
	header, _ := json.Marshal([]any{scope.Root.ID, days})
	hash.Write(header)
	seen := map[string]bool{}
	rootFound := false
	var eligible time.Time
	for _, item := range items {
		entry := item.Entry
		entry.CoverURL = nil
		if seen[entry.ID] || !ValidJournalID(entry.ID) || entry.State != "trashed" || entry.Target.ComicID != scope.Root.Target.ComicID || !validDigest(item.PlanHash) || ValidateCandidates(item.Candidates) != nil {
			return value, ErrConflict
		}
		seen[entry.ID] = true
		if entry.ID == scope.Root.ID {
			rootFound = true
		} else if scope.Root.Target.Type == "media" || scope.Root.Target.Type == "chapter" && (entry.Target.Type != "media" || entry.Target.ChapterID != scope.Root.Target.ID) {
			return value, ErrConflict
		}
		trashedAt, err := time.Parse(time.RFC3339Nano, entry.UpdatedAt)
		if err != nil {
			return value, ErrJournal
		}
		until := trashedAt.Add(time.Duration(days) * 24 * time.Hour)
		if until.After(eligible) {
			eligible = until
		}
		value.EntryCount++
		value.FileCount += len(item.Candidates)
		if value.FileCount > MaxFiles {
			return value, ErrValidation
		}
		for _, file := range item.Candidates {
			if file.Size < 0 {
				value.UnmeasuredFiles++
			} else {
				value.RegisteredBytes += file.Size
			}
		}
		data, _ := json.Marshal([]any{entry, item.PlanHash})
		hash.Write(data)
	}
	if !rootFound {
		return value, ErrConflict
	}
	value.Stamp = hex.EncodeToString(hash.Sum(nil))
	value.EligibleAt = eligible.UTC().Format(time.RFC3339Nano)
	value.Allowed = !now.Before(eligible)
	if !value.Allowed {
		value.Blockers = append(value.Blockers, "RETENTION_ACTIVE")
	}
	return value, nil
}
