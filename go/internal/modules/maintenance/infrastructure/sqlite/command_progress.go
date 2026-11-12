package sqlite

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	"context"
	"database/sql"
	"errors"
	"time"
)

func (repo *repository) CommandParents(ctx context.Context) ([]string, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT id FROM tasks WHERE type='command_batch' AND status IN('queued','running','cancelling') ORDER BY created_at,id LIMIT ?", jobsdomain.MaxBatchParents)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
func (repo *repository) StartCommand(ctx context.Context, id string) (maintenancedomain.CommandParent, error) {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return maintenancedomain.CommandParent{}, err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "UPDATE tasks SET status='running',stage='coordinating',attempt=attempt+1,started_at=?,finished_at=NULL,error_code=NULL WHERE id=? AND type='command_batch' AND status='queued'", time.Now().UTC().Format(jobsdomain.TimestampFormat), id); err != nil {
		return maintenancedomain.CommandParent{}, err
	}
	value := maintenancedomain.CommandParent{TaskID: id}
	err = tx.QueryRowContext(ctx, "SELECT p.id,t.status,o.options_json FROM tasks t JOIN batch_previews p ON p.task_id=t.id JOIN batch_preview_options o ON o.preview_id=p.id WHERE t.id=? AND t.type='command_batch' AND t.status IN('running','cancelling')", id).Scan(&value.PreviewID, &value.Status, &value.OptionsJSON)
	if errors.Is(err, sql.ErrNoRows) {
		return value, jobsdomain.NewFailure(jobsdomain.FailureInternal, nil)
	}
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}
func (repo *repository) ReconcileCommand(ctx context.Context, id string) (bool, error) {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var preview, status string
	if err = tx.QueryRowContext(ctx, "SELECT p.id,t.status FROM tasks t JOIN batch_previews p ON p.task_id=t.id WHERE t.id=? AND t.type='command_batch' AND t.status IN('running','cancelling')", id).Scan(&preview, &status); err != nil {
		return false, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT i.position,t.status,t.error_code FROM batch_preview_items i JOIN tasks t ON t.id=i.child_task_id WHERE i.preview_id=? AND i.status='dispatched' AND t.status IN('succeeded','failed','cancelled') ORDER BY i.position", preview)
	if err != nil {
		return false, err
	}
	observations := []maintenancedomain.ChildObservation{}
	for rows.Next() {
		var child maintenancedomain.ChildObservation
		if err = rows.Scan(&child.Position, &child.Status, &child.ErrorCode); err != nil {
			rows.Close()
			return false, err
		}
		observations = append(observations, child)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return false, err
	}
	for _, child := range observations {
		outcome, terminal := maintenancedomain.ChildOutcome(child.Status, child.ErrorCode)
		if !terminal {
			continue
		}
		if _, err = tx.ExecContext(ctx, "UPDATE batch_preview_items SET status=?,error_code=?,wait_reason=NULL WHERE preview_id=? AND position=? AND status='dispatched'", outcome.Status, outcome.ErrorCode, preview, child.Position); err != nil {
			return false, err
		}
	}
	if status == "cancelling" {
		if _, err = tx.ExecContext(ctx, "UPDATE batch_preview_items SET status='cancelled',error_code='BATCH_CANCELLED',wait_reason=NULL WHERE preview_id=? AND status='pending' AND covered_by IS NULL", preview); err != nil {
			return false, err
		}
	}
	if err = reconcileCovered(ctx, tx, preview); err != nil {
		return false, err
	}
	var progress maintenancedomain.CommandProgress
	if err = tx.QueryRowContext(ctx, "SELECT total_count,pending_count,active_count,cancelled_count FROM batch_preview_counts WHERE preview_id=?", preview).Scan(&progress.Total, &progress.Pending, &progress.Active, &progress.Cancelled); err != nil {
		return false, err
	}
	progress.Completed = progress.Total - progress.Pending - progress.Active
	if _, err = tx.ExecContext(ctx, "UPDATE tasks SET total_count=?,completed_count=? WHERE id=? AND (total_count IS NOT ? OR completed_count<>?)", progress.Total, progress.Completed, id, progress.Total, progress.Completed); err != nil {
		return false, err
	}
	terminal := maintenancedomain.CommandTerminal(status, progress)
	if terminal != "" {
		stamp := time.Now().UTC().Format(jobsdomain.TimestampFormat)
		if _, err = tx.ExecContext(ctx, "UPDATE tasks SET status=?,stage='completed',finished_at=?,error_code=NULL WHERE id=?", terminal, stamp, id); err != nil {
			return false, err
		}
		if _, err = tx.ExecContext(ctx, "UPDATE batch_previews SET completed_at=? WHERE id=?", stamp, preview); err != nil {
			return false, err
		}
	}
	return terminal != "", tx.Commit()
}
func (repo *repository) FailCommand(ctx context.Context, id string) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var task jobsdomain.Task
	err = tx.QueryRowContext(ctx, "SELECT status,stage,attempt,started_at FROM tasks WHERE id=? AND type='command_batch' AND status IN('queued','running')", id).Scan(&task.Status, &task.Stage, &task.Attempt, &task.StartedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	task = maintenancedomain.FailedCommandTask(task, time.Now().UTC().Format(jobsdomain.TimestampFormat))
	if _, err = tx.ExecContext(ctx, "UPDATE tasks SET status=?,stage=?,attempt=?,started_at=?,error_code=?,finished_at=? WHERE id=?", task.Status, task.Stage, task.Attempt, task.StartedAt, task.ErrorCode, task.FinishedAt, id); err != nil {
		return err
	}
	return tx.Commit()
}
