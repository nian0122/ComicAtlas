package sqlite

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"time"
)

func completePurge(ctx context.Context, tx *sql.Tx, task string, entry lifecycledomain.Entry, current lifecycledomain.Target) error {
	if current.State != "purging" || entry.State != "purging" {
		return lifecycledomain.ErrConflict
	}
	if _, err := purgeRun(ctx, tx, task, "purging"); err != nil {
		return err
	}
	result, err := tx.ExecContext(ctx, "DELETE FROM "+targetTable(current.Type)+" WHERE id=? AND lifecycle_state='purging'", current.ID)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return lifecycledomain.ErrConflict
	}
	if _, err = tx.ExecContext(ctx, "UPDATE lifecycle_entries SET state='purged',updated_at=? WHERE id IN(SELECT entry_id FROM lifecycle_purge_entries WHERE task_id=?)", time.Now().UTC().Format(jobsdomain.TimestampFormat), task); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM lifecycle_cover_sources WHERE entry_id IN(SELECT entry_id FROM lifecycle_purge_entries WHERE task_id=?)", task); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "UPDATE lifecycle_runs SET completed=1 WHERE task_id=?", task)
	return err
}

func (repo *repository) FailPurgePreflight(task string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		result, err := tx.ExecContext(ctx, `UPDATE lifecycle_runs SET completed=1 WHERE task_id=? AND operation='purge' AND completed=0
		 AND EXISTS(SELECT 1 FROM lifecycle_entries WHERE id=lifecycle_runs.entry_id AND state='trashed')`, task)
		if err != nil {
			return err
		}
		count, err := result.RowsAffected()
		if err != nil || count == 0 {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE tasks SET stage=? WHERE id=?", jobsdomain.StagePurgePreflightFailed, task)
		return err
	}
}
