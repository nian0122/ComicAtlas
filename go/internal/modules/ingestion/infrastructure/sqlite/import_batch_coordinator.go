package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"time"

	ingestionports "comicatlas.local/backend/internal/modules/ingestion/application/ports"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

var _ ingestionports.ScanRepository = (*ScanRepository)(nil)

func (repo *ScanRepository) ImportBatchParents(ctx context.Context) ([]string, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, `SELECT t.id FROM tasks t JOIN task_batches b ON b.parent_task_id=t.id
 WHERE t.type='import_batch' AND t.status IN('queued','running','cancelling') ORDER BY t.created_at,t.id LIMIT ?`, jobsdomain.MaxBatchParents)
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

func (repo *ScanRepository) StartImportBatch(ctx context.Context, id string) error {
	_, err := repo.database.Writer.ExecContext(ctx, `UPDATE tasks SET status='running',stage='coordinating',attempt=attempt+1,started_at=?,finished_at=NULL,error_code=NULL
		WHERE id=? AND type='import_batch' AND status='queued'`, time.Now().UTC().Format(jobsdomain.TimestampFormat), id)
	return err
}

func (repo *ScanRepository) ReconcileImportBatch(ctx context.Context, id string) (bool, bool, error) {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return false, false, err
	}
	defer tx.Rollback()
	var batch, status string
	if err = tx.QueryRowContext(ctx, `SELECT b.id,t.status FROM tasks t JOIN task_batches b ON b.parent_task_id=t.id
 WHERE t.id=? AND t.type='import_batch' AND t.status IN('running','cancelling')`, id).Scan(&batch, &status); errors.Is(err, sql.ErrNoRows) {
		return true, false, nil
	} else if err != nil {
		return false, false, err
	}
	rows, err := tx.QueryContext(ctx, `SELECT i.position,t.status,t.error_code FROM import_batch_dispatch_items i JOIN tasks t ON t.id=i.task_id
 WHERE i.batch_id=? AND i.status='dispatched' AND t.status IN('succeeded','failed','cancelled') ORDER BY i.position`, batch)
	if err != nil {
		return false, false, err
	}
	type outcome struct {
		position int
		status   string
		code     sql.NullString
	}
	children := []outcome{}
	for rows.Next() {
		var item outcome
		if err = rows.Scan(&item.position, &item.status, &item.code); err != nil {
			rows.Close()
			return false, false, err
		}
		children = append(children, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return false, false, err
	}
	for _, child := range children {
		if _, err = tx.ExecContext(ctx, `UPDATE import_batch_dispatch_items SET status=?,error_code=? WHERE batch_id=? AND position=? AND status='dispatched'`, child.status, nullableString(child.code), batch, child.position); err != nil {
			return false, false, err
		}
	}
	if status == jobsdomain.TaskStatusCancelling {
		if _, err = tx.ExecContext(ctx, `UPDATE import_batch_dispatch_items SET status='cancelled',error_code='BATCH_CANCELLED'
 WHERE batch_id=? AND status='pending'`, batch); err != nil {
			return false, false, err
		}
	}
	var total, pending, active, completed int
	if err = tx.QueryRowContext(ctx, `SELECT COUNT(*),COALESCE(SUM(status='pending'),0),COALESCE(SUM(status='dispatched'),0),
 COALESCE(SUM(status IN('succeeded','failed','cancelled')),0) FROM import_batch_dispatch_items WHERE batch_id=?`, batch).Scan(&total, &pending, &active, &completed); err != nil {
		return false, false, err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE tasks SET total_count=?,completed_count=? WHERE id=? AND (total_count IS NOT ? OR completed_count<>?)`, total, completed, id, total, completed); err != nil {
		return false, false, err
	}
	if err = tx.Commit(); err != nil {
		return false, false, err
	}
	return pending == 0 && active == 0, status == jobsdomain.TaskStatusCancelling, nil
}

func nullableString(value sql.NullString) any {
	if !value.Valid {
		return nil
	}
	return value.String
}

func (repo *ScanRepository) PendingImportBatchItem(ctx context.Context, id string) (int, string, error) {
	var position int
	var data string
	err := repo.database.Reader.QueryRowContext(ctx, `SELECT i.position,i.prepared_json FROM import_batch_dispatch_items i
 JOIN task_batches b ON b.id=i.batch_id JOIN tasks t ON t.id=b.parent_task_id
 WHERE t.id=? AND t.status='running' AND i.status='pending' ORDER BY i.position LIMIT 1`, id).Scan(&position, &data)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, "", jobsdomain.ErrNotFound
	}
	if err != nil {
		return 0, "", err
	}
	return position, data, nil
}

func (repo *ScanRepository) ActiveImportBatchChildren(ctx context.Context, id string) ([]string, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, `SELECT i.task_id FROM import_batch_dispatch_items i JOIN task_batches b ON b.id=i.batch_id
 JOIN tasks t ON t.id=i.task_id WHERE b.parent_task_id=? AND t.status IN('queued','running','cancelling') ORDER BY i.position`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := []string{}
	for rows.Next() {
		var child string
		if err = rows.Scan(&child); err != nil {
			return nil, err
		}
		ids = append(ids, child)
	}
	return ids, rows.Err()
}

func (repo *ScanRepository) ImportBatchChildAdmission(parent string, position int) jobsports.Admission {
	return func(child string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			result, err := tx.ExecContext(ctx, `UPDATE import_batch_dispatch_items SET status='dispatched',task_id=? WHERE batch_id=
			 (SELECT b.id FROM task_batches b JOIN tasks p ON p.id=b.parent_task_id WHERE p.id=? AND p.type='import_batch' AND p.status='running') AND position=? AND status='pending' AND task_id IS NULL
			 AND EXISTS(SELECT 1 FROM tasks c WHERE c.id=? AND c.source_path=import_batch_dispatch_items.source_path AND c.type IN('directory_import','archive_import'))`, child, parent, position, child)
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
}

func (repo *ScanRepository) ImportBatchCompletion(parent string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var pending int
		if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM import_batch_dispatch_items i JOIN task_batches b ON b.id=i.batch_id
 WHERE b.parent_task_id=? AND i.status IN('pending','dispatched')`, parent).Scan(&pending); err != nil {
			return err
		}
		if pending != 0 {
			return jobsdomain.ErrConflict
		}
		return nil
	}
}
