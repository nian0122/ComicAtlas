package sqlite

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"time"
)

func (repo *repository) BeginTrashMove(ctx context.Context, task string) error {
	result, err := repo.database.Writer.ExecContext(ctx, "UPDATE lifecycle_runs SET trash_move_started=1 WHERE task_id=? AND operation='trash' AND completed=0", task)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err == nil && count != 1 {
		return lifecycledomain.ErrConflict
	}
	return err
}

func (repo *repository) FailTrashPreparation(task string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var entryID, comicID string
		if err := tx.QueryRowContext(ctx, `SELECT e.id,e.comic_id FROM lifecycle_runs r JOIN lifecycle_entries e ON e.id=r.entry_id
		 WHERE r.task_id=? AND r.operation='trash' AND r.completed=0 AND r.trash_move_started=0
		 AND e.task_id=r.task_id AND e.target_type='comic' AND e.state='trashing'`, task).Scan(&entryID, &comicID); err != nil {
			return err
		}
		result, err := tx.ExecContext(ctx, "UPDATE comics SET lifecycle_state='active' WHERE id=? AND lifecycle_state='trashing'", comicID)
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
		if _, err = tx.ExecContext(ctx, "UPDATE lifecycle_entries SET state='restored',updated_at=? WHERE id=?", time.Now().UTC().Format(jobsdomain.TimestampFormat), entryID); err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, "DELETE FROM lifecycle_cover_sources WHERE entry_id=?", entryID); err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, "UPDATE lifecycle_runs SET completed=1 WHERE task_id=?", task); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE tasks SET stage=? WHERE id=?", jobsdomain.StageTrashPreparationFailed, task)
		return err
	}
}
