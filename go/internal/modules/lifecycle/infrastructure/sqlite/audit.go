package sqlite

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

func (repo *repository) AuditAdmission(expected lifecycledomain.AuditSnapshot) jobsports.Admission {
	return func(task string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			current, err := auditSnapshot(ctx, tx, expected.Requested.ID)
			if err != nil {
				return err
			}
			stamp, total, err := lifecycledomain.AuditStamp(current)
			if err != nil {
				return err
			}
			before, _, err := lifecycledomain.AuditStamp(expected)
			if err != nil {
				return err
			}
			if stamp != before {
				return lifecycledomain.ErrConflict
			}
			if err = blockChangesForOwner(ctx, tx, current.Requested.Target.ComicID, task, current.OwnerTaskID); err != nil {
				return err
			}
			var ownerTask any
			if current.OwnerTaskID != "" {
				ownerTask = current.OwnerTaskID
			}
			_, err = tx.ExecContext(ctx, "INSERT INTO lifecycle_audits(task_id,entry_id,owner_task_id,snapshot_hash,total_files,created_at) VALUES(?,?,?,?,?,?)", task, current.Requested.ID, ownerTask, stamp, total, time.Now().UTC().Format(jobsdomain.TimestampFormat))
			return err
		}
	}
}

func (repo *repository) BeginAudit(ctx context.Context, task string) (lifecycledomain.AuditSnapshot, error) {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return lifecycledomain.AuditSnapshot{}, err
	}
	defer tx.Rollback()
	var entry, expected string
	if err = tx.QueryRowContext(ctx, "SELECT entry_id,snapshot_hash FROM lifecycle_audits WHERE task_id=? AND complete=0", task).Scan(&entry, &expected); err != nil {
		return lifecycledomain.AuditSnapshot{}, err
	}
	value, err := auditSnapshot(ctx, tx, entry)
	if err != nil {
		return value, err
	}
	stamp, _, err := lifecycledomain.AuditStamp(value)
	if err != nil {
		return value, err
	}
	if stamp != expected {
		return value, lifecycledomain.ErrConflict
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM lifecycle_audit_items WHERE task_id=?", task); err != nil {
		return value, err
	}
	return value, tx.Commit()
}

func (repo *repository) RecordAudit(ctx context.Context, task string, items []lifecycledomain.FileObservation) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var active bool
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM tasks WHERE id=? AND status='running')", task).Scan(&active); err != nil {
		return err
	}
	if !active {
		return context.Canceled
	}
	statement, err := tx.PrepareContext(ctx, "INSERT INTO lifecycle_audit_items(task_id,position,entry_id,item_json) VALUES(?,?,?,?)")
	if err != nil {
		return err
	}
	defer statement.Close()
	for _, item := range items {
		data, err := json.Marshal(item)
		if err != nil {
			return err
		}
		if _, err = statement.ExecContext(ctx, task, item.Position, item.EntryID, string(data)); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (repo *repository) AuditCompletion(task string, summary lifecycledomain.AuditSummary) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var entry, expected string
		if err := tx.QueryRowContext(ctx, "SELECT entry_id,snapshot_hash FROM lifecycle_audits WHERE task_id=? AND complete=0", task).Scan(&entry, &expected); err != nil {
			return err
		}
		current, err := auditSnapshot(ctx, tx, entry)
		if err != nil {
			return err
		}
		stamp, total, err := lifecycledomain.AuditStamp(current)
		if err != nil {
			return err
		}
		if stamp != expected || summary.TaskID != task || summary.EntryID != entry || summary.OwnerTaskID != current.OwnerTaskID || !summary.Complete || summary.TotalFiles != total {
			return lifecycledomain.ErrConflict
		}
		var count int
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM lifecycle_audit_items WHERE task_id=?", task).Scan(&count); err != nil {
			return err
		}
		if count != summary.TotalFiles || count != summary.CheckedFiles {
			return lifecycledomain.ErrConflict
		}
		data, err := json.Marshal(summary)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE lifecycle_audits SET complete=1,summary_json=? WHERE task_id=?", string(data), task)
		return err
	}
}

func (repo *repository) AuditSummary(ctx context.Context, entry string) (lifecycledomain.AuditSummary, error) {
	var value lifecycledomain.AuditSummary
	var data *string
	var ownerTaskID sql.NullString
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT task_id,entry_id,owner_task_id,total_files,complete,summary_json FROM lifecycle_audits WHERE entry_id=? ORDER BY created_at DESC,task_id DESC LIMIT 1", entry).Scan(&value.TaskID, &value.EntryID, &ownerTaskID, &value.TotalFiles, &value.Complete, &data)
	if errors.Is(err, sql.ErrNoRows) {
		return value, lifecycledomain.ErrNotFound
	}
	if err != nil {
		return value, err
	}
	if ownerTaskID.Valid {
		value.OwnerTaskID = ownerTaskID.String
	}
	if data != nil {
		err = json.Unmarshal([]byte(*data), &value)
	} else {
		err = repo.database.Reader.QueryRowContext(ctx, "SELECT COUNT(*) FROM lifecycle_audit_items WHERE task_id=?", value.TaskID).Scan(&value.CheckedFiles)
	}
	return value, err
}

func (repo *repository) AuditItems(ctx context.Context, task string, page, size int) (lifecycledomain.AuditPage, error) {
	value := lifecycledomain.AuditPage{TaskID: task, Page: page, PageSize: size, Items: []lifecycledomain.FileObservation{}}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return value, err
	}
	defer tx.Rollback()
	if err = tx.QueryRowContext(ctx, "SELECT complete FROM lifecycle_audits WHERE task_id=?", task).Scan(&value.Complete); errors.Is(err, sql.ErrNoRows) {
		return value, lifecycledomain.ErrNotFound
	} else if err != nil {
		return value, err
	}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM lifecycle_audit_items WHERE task_id=?", task).Scan(&value.Total); err != nil {
		return value, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT item_json FROM lifecycle_audit_items WHERE task_id=? ORDER BY position LIMIT ? OFFSET ?", task, size, (page-1)*size)
	if err != nil {
		return value, err
	}
	for rows.Next() {
		var data string
		var item lifecycledomain.FileObservation
		if err = rows.Scan(&data); err != nil {
			rows.Close()
			return value, err
		}
		if err = json.Unmarshal([]byte(data), &item); err != nil {
			rows.Close()
			return value, err
		}
		value.Items = append(value.Items, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}

func (repo *repository) RepairGuard(task string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var entry, expected, data string
		if err := tx.QueryRowContext(ctx, "SELECT entry_id,snapshot_hash,summary_json FROM lifecycle_audits WHERE task_id=? AND complete=1", task).Scan(&entry, &expected, &data); err != nil {
			return err
		}
		var summary lifecycledomain.AuditSummary
		if json.Unmarshal([]byte(data), &summary) != nil || !summary.RepairAvailable {
			return lifecycledomain.ErrConflict
		}
		current, err := auditSnapshot(ctx, tx, entry)
		if err != nil {
			return err
		}
		stamp, _, err := lifecycledomain.AuditStamp(current)
		if err != nil {
			return err
		}
		if stamp != expected || summary.TaskID != task || summary.EntryID != entry || summary.OwnerTaskID != current.OwnerTaskID || !summary.Complete || current.OwnerState != "failed" {
			return lifecycledomain.ErrConflict
		}
		return nil
	}
}
