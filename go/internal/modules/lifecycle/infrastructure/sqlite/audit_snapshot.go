package sqlite

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"database/sql"
	"errors"
	"strings"
)

func auditSnapshot(ctx context.Context, tx *sql.Tx, id string) (lifecycledomain.AuditSnapshot, error) {
	var value lifecycledomain.AuditSnapshot
	entry, err := scanEntry(tx.QueryRowContext(ctx, "SELECT "+entryColumns+" FROM lifecycle_entries WHERE id=?", id))
	if err != nil {
		return value, err
	}
	value.Requested = entry
	value.OwnerTaskID = entry.TaskID
	if entry.Origin == "legacy" && entry.TaskID == "" {
		if entry.State != "trashed" {
			return value, lifecycledomain.ErrConflict
		}
		finishedAt := entry.CreatedAt
		value.OwnerState, value.Operation, value.OwnerFinishedAt = "imported", "import", &finishedAt
	} else if err = tx.QueryRowContext(ctx, "SELECT t.status,t.attempt,t.finished_at,r.operation FROM tasks t JOIN lifecycle_runs r ON r.task_id=t.id WHERE t.id=?", entry.TaskID).Scan(&value.OwnerState, &value.OwnerAttempt, &value.OwnerFinishedAt, &value.Operation); err != nil {
		return value, err
	}
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM lifecycle_entries WHERE target_type=? AND target_id=? AND (created_at>? OR (created_at=? AND id>?)))", entry.Target.Type, entry.Target.ID, entry.CreatedAt, entry.CreatedAt, entry.ID).Scan(&value.Superseded); err != nil {
		return value, err
	}
	query := "SELECT " + entryColumns + ",plan_json FROM lifecycle_entries WHERE id=?"
	args := []any{id}
	if value.Operation == "purge" {
		columns := "e." + strings.ReplaceAll(entryColumns, ",", ",e.")
		query = "SELECT " + columns + ",e.plan_json FROM lifecycle_purge_entries p JOIN lifecycle_entries e ON e.id=p.entry_id WHERE p.task_id=? ORDER BY e.id LIMIT ?"
		args = []any{entry.TaskID, lifecycledomain.MaxFiles + 1}
	}
	rows, err := tx.QueryContext(ctx, query, args...)
	if err != nil {
		return value, err
	}
	totalBytes, totalFiles := 0, 0
	for rows.Next() {
		item, size, err := scanPurgeItem(rows, false)
		if err != nil {
			rows.Close()
			return value, err
		}
		totalBytes += size
		totalFiles += len(item.Candidates)
		if totalBytes > lifecycledomain.MaxJournalBytes || totalFiles > lifecycledomain.MaxFiles || len(value.Entries) >= lifecycledomain.MaxFiles {
			rows.Close()
			return value, lifecycledomain.ErrValidation
		}
		value.Entries = append(value.Entries, lifecycledomain.AuditEntry{Entry: item.Entry, Candidates: item.Candidates, PlanHash: item.PlanHash})
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return value, err
	}
	for index := range value.Entries {
		item := &value.Entries[index]
		object, err := target(ctx, tx, item.Entry.Target.Type, item.Entry.Target.ID)
		if errors.Is(err, lifecycledomain.ErrNotFound) {
			item.ObjectState = "absent"
		} else if err != nil {
			return value, err
		} else {
			item.ObjectState = object.State
		}
	}
	_, _, err = lifecycledomain.AuditStamp(value)
	return value, err
}

func (repo *repository) AuditSnapshot(ctx context.Context, id string) (lifecycledomain.AuditSnapshot, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return lifecycledomain.AuditSnapshot{}, err
	}
	defer tx.Rollback()
	value, err := auditSnapshot(ctx, tx, id)
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}
