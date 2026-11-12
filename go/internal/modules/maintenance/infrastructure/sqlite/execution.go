package sqlite

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"time"
)

func (repo *repository) Admission(id, hash string) jobsports.Admission {
	return func(task string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			record, err := readPreview(ctx, tx, id)
			if err != nil {
				return err
			}
			if err = maintenancedomain.ValidateDangerousConfirmation(record.Preview.Operation, record.ConfirmationHash, hash); err != nil {
				return err
			}
			if err = maintenancedomain.ValidateConfirmation(record.Preview.TaskID, record.Preview.ExpiresAt, record.Preview.EligibleCount, time.Now().UTC()); err != nil {
				return err
			}
			var kind, target string
			if err = tx.QueryRowContext(ctx, "SELECT type,target_type FROM tasks WHERE id=?", task).Scan(&kind, &target); err != nil {
				return err
			}
			if !maintenancedomain.MatchesBatchResource(record.Preview.Operation, kind, target) {
				return jobsdomain.ErrConflict
			}
			if maintenancedomain.IsCommand(record.Preview.Operation) {
				var count int
				if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE type='command_batch' AND id<>? AND status IN('queued','running','cancelling')", task).Scan(&count); err != nil {
					return err
				}
				if err = maintenancedomain.ValidateCommandCapacity(count, 0); err != nil {
					return err
				}
			}
			_, err = tx.ExecContext(ctx, "UPDATE batch_previews SET task_id=? WHERE id=?", task, id)
			return err
		}
	}
}
func (repo *repository) Next(ctx context.Context, task string) (maintenancedomain.Item, error) {
	return scanItem(repo.database.Reader.QueryRowContext(ctx, "SELECT "+itemColumns+" FROM batch_preview_items i JOIN batch_previews p ON p.id=i.preview_id WHERE p.task_id=? AND i.status='pending' AND i.covered_by IS NULL ORDER BY i.position LIMIT 1", task))
}
func (repo *repository) Apply(ctx context.Context, task string, item maintenancedomain.Item, action persistence.WriteAction) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var preview string
	if err = tx.QueryRowContext(ctx, `SELECT p.id FROM batch_previews p JOIN tasks t ON t.id=p.task_id JOIN batch_preview_items i ON i.preview_id=p.id
 WHERE p.task_id=? AND t.status='running' AND i.position=? AND i.comic_id=? AND i.version=? AND i.status='pending'`, task, item.Position, item.ComicID, item.Version).Scan(&preview); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "SAVEPOINT metadata_item"); err != nil {
		return err
	}
	writeErr := action(ctx, tx)
	outcome, err := maintenancedomain.AppliedOutcome(item, writeErr)
	if err != nil {
		return err
	}
	if writeErr != nil {
		if _, err = tx.ExecContext(ctx, "ROLLBACK TO metadata_item"); err != nil {
			return err
		}
	}
	if _, err = tx.ExecContext(ctx, "RELEASE metadata_item"); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE batch_preview_items SET status=?,error_code=?,updated_version=? WHERE preview_id=? AND position=?", outcome.Status, outcome.ErrorCode, outcome.UpdatedVersion, preview, item.Position); err != nil {
		return err
	}
	progress, err := tx.ExecContext(ctx, "UPDATE tasks SET completed_count=completed_count+1 WHERE id=? AND status='running' AND completed_count<total_count", task)
	if err != nil {
		return err
	}
	count, err := progress.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return jobsdomain.ErrConflict
	}
	return tx.Commit()
}
func (repo *repository) Completion(task string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		result, err := tx.ExecContext(ctx, `UPDATE batch_previews SET completed_at=? WHERE task_id=? AND NOT EXISTS(SELECT 1 FROM batch_preview_items WHERE preview_id=batch_previews.id AND status='pending')`, time.Now().UTC().Format(jobsdomain.TimestampFormat), task)
		if err != nil {
			return err
		}
		count, err := result.RowsAffected()
		if err != nil {
			return err
		}
		if count != 1 {
			return jobsdomain.ErrConflict
		}
		return nil
	}
}
