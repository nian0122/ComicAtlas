package sqlite

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"time"
)

func (repo *repository) Completion(id string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var entryID, operation string
		if err := tx.QueryRowContext(ctx, "SELECT entry_id,operation FROM lifecycle_runs WHERE task_id=? AND completed=0", id).Scan(&entryID, &operation); err != nil {
			return err
		}
		if operation != "purge" {
			if err := completeFileResults(ctx, tx, id); err != nil {
				return err
			}
		} else {
			// 永久清理已改为目录级，删除旧版本可能留下的逐文件报告。
			if _, err := tx.ExecContext(ctx, "DELETE FROM lifecycle_file_results WHERE task_id=?", id); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, "DELETE FROM lifecycle_file_reports WHERE task_id=?", id); err != nil {
				return err
			}
		}
		entry, err := scanEntry(tx.QueryRowContext(ctx, "SELECT "+entryColumns+" FROM lifecycle_entries WHERE id=? AND task_id=?", entryID, id))
		if err != nil {
			return err
		}
		current, err := target(ctx, tx, entry.Target.Type, entry.Target.ID)
		if err != nil {
			return err
		}
		if operation == "purge" {
			return completePurge(ctx, tx, id, entry, current)
		}
		state := "trashed"
		if operation == "trash" {
			if current.State != "trashing" || entry.State != "trashing" {
				return lifecycledomain.ErrConflict
			}
			if _, err = tx.ExecContext(ctx, "UPDATE "+targetTable(current.Type)+" SET lifecycle_state='trashed' WHERE id=?", current.ID); err != nil {
				return err
			}
		} else {
			if current.State != "restoring" || entry.State != "restoring" {
				return lifecycledomain.ErrConflict
			}
			if err = repo.reactivate(ctx, tx, current); err != nil {
				return err
			}
			state = "restored"
			if _, err = tx.ExecContext(ctx, "DELETE FROM lifecycle_cover_sources WHERE entry_id=?", entryID); err != nil {
				return err
			}
		}
		if _, err = tx.ExecContext(ctx, "UPDATE lifecycle_entries SET state=?,updated_at=? WHERE id=?", state, time.Now().UTC().Format(jobsdomain.TimestampFormat), entryID); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE lifecycle_runs SET completed=1 WHERE task_id=?", id)
		return err
	}
}
