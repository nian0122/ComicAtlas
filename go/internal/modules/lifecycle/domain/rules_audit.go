package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"path"
	"sort"
	"strings"
)

func FileLabel(name string) (string, string) {
	role, _, _ := strings.Cut(name, "/")
	return role, path.Base(name)
}

func AuditStamp(value AuditSnapshot) (string, int, error) {
	imported := value.Requested.Origin == "legacy" && value.Requested.State == "trashed" && value.OwnerTaskID == "" && value.OwnerState == "imported" && value.OwnerAttempt == 0 && value.Operation == "import" && value.OwnerFinishedAt != nil && *value.OwnerFinishedAt == value.Requested.CreatedAt
	if len(value.Entries) == 0 || len(value.Entries) > MaxFiles || !ValidJournalID(value.Requested.ID) || (!imported && !ValidJournalID(value.OwnerTaskID)) {
		return "", 0, ErrJournal
	}
	items := append([]AuditEntry{}, value.Entries...)
	sort.Slice(items, func(i, j int) bool { return items[i].Entry.ID < items[j].Entry.ID })
	hash := sha256.New()
	header, _ := json.Marshal([]any{value.Requested.ID, value.OwnerTaskID, value.OwnerState, value.OwnerAttempt, value.OwnerFinishedAt, value.Operation, value.Superseded})
	hash.Write(header)
	count := 0
	for _, item := range items {
		if ValidateCandidates(item.Candidates) != nil || !validDigest(item.PlanHash) {
			return "", 0, ErrJournal
		}
		count += len(item.Candidates)
		if count > MaxFiles {
			return "", 0, ErrValidation
		}
		entry := item.Entry
		entry.CoverURL = nil
		data, _ := json.Marshal([]any{entry, item.PlanHash, item.ObjectState})
		hash.Write(data)
	}
	return hex.EncodeToString(hash.Sum(nil)), count, nil
}

func AuditEntryState(entry AuditEntry, journal JournalObservation) (bool, bool) {
	expected := entry.Entry.State
	if expected == "restored" {
		expected = "active"
	}
	if entry.ObjectState != expected || journal.MoveConflict && entry.Entry.State != "purging" {
		return false, false
	}
	stable := (entry.Entry.State == "trashed" && journal.Phase == "trashed") || (entry.Entry.State == "restored" && journal.Phase == "restored")
	if journal.State == "valid" && stable {
		return true, false
	}
	if entry.Entry.State == "trashing" {
		return false, journal.State == "missing" || journal.State == "valid" && (journal.Phase == "prepared" || journal.Phase == "trashed")
	}
	if entry.Entry.State == "restoring" {
		return false, journal.State == "valid" && (journal.Phase == "trashed" || journal.Phase == "restoring" || journal.Phase == "restored")
	}
	if entry.Entry.State == "purging" {
		return false, journal.State == "valid" && (journal.Phase == "trashed" || journal.Phase == "purging" || journal.Phase == "purged")
	}
	return false, false
}

func EvaluateFileObservation(item FileObservation, state string) FileObservation {
	source, retained := item.SourceState, item.RetainedState
	valid := func(value string) bool { return value == "valid" || value == "unmeasured" }
	absent := source == "absent" && retained == "absent"
	initialMissing := item.Prepared && item.InitiallyMissing && absent
	trashed := source == "absent" && valid(retained) || initialMissing
	restored := valid(source) && retained == "absent" || initialMissing
	if item.JournalState == "valid" {
		switch state {
		case "trashed":
			item.Consistent = item.JournalPhase == "trashed" && trashed
		case "restored":
			item.Consistent = item.JournalPhase == "restored" && restored
		case "trashing":
			item.Resumable = item.JournalPhase == "prepared" && (trashed || restored) || item.JournalPhase == "trashed" && trashed
		case "restoring":
			item.Resumable = (item.JournalPhase == "trashed" || item.JournalPhase == "restoring") && (trashed || restored) || item.JournalPhase == "restored" && restored
		case "purging":
			item.Resumable = (item.JournalPhase == "trashed" || item.JournalPhase == "purging" || item.JournalPhase == "purged") && (retained == "absent" || valid(retained) && !item.InitiallyMissing)
		}
	} else if item.JournalState == "missing" && state == "trashing" {
		item.Resumable = retained == "absent" && (source == "absent" || valid(source))
	}
	if item.Consistent {
		return item
	}
	item.ErrorCode = "OPERATION_PENDING"
	if !item.Resumable {
		item.ErrorCode = "FILE_LOCATION_CONFLICT"
		if absent && !initialMissing {
			item.ErrorCode = "FILE_MISSING"
		}
		if source == "changed" || retained == "changed" {
			item.ErrorCode = "FILE_CHANGED"
		}
		if source == "unsafe" || retained == "unsafe" {
			item.ErrorCode = "FILE_UNSAFE"
		}
		if source == "unreadable" || retained == "unreadable" {
			item.ErrorCode = "FILE_UNREADABLE"
		}
		if item.JournalState != "valid" {
			item.ErrorCode = "JOURNAL_" + strings.ToUpper(item.JournalState)
		}
	}
	return item
}

func Repairable(summary AuditSummary, resumable bool) bool {
	return summary.Complete && !summary.Superseded && summary.OwnerState == "failed" && (summary.EntryState == "trashing" || summary.EntryState == "restoring" || summary.EntryState == "purging") && resumable
}
