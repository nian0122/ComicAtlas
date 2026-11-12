package sqlite

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

func (repo *repository) BeginFileResults(ctx context.Context, task string, attempt int, operation string, total int) error {
	if err := lifecycledomain.ValidateFileReport(attempt, operation, total); err != nil {
		return err
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var active bool
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM tasks t JOIN lifecycle_runs r ON r.task_id=t.id WHERE t.id=? AND t.type='lifecycle' AND t.status='running' AND t.attempt=? AND r.operation=? AND r.completed=0)", task, attempt, operation).Scan(&active); err != nil {
		return err
	}
	if !active {
		return lifecycledomain.ErrConflict
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM lifecycle_file_results WHERE task_id=?", task); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO lifecycle_file_reports(task_id,attempt,operation,total_files) VALUES(?,?,?,?) ON CONFLICT(task_id) DO UPDATE SET attempt=excluded.attempt,operation=excluded.operation,total_files=excluded.total_files,complete=0", task, attempt, operation, total); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE tasks SET stage=?,total_count=?,completed_count=0 WHERE id=?", operation, total, task); err != nil {
		return err
	}
	return tx.Commit()
}

func (repo *repository) RecordFileResults(ctx context.Context, task string, attempt int, items []lifecycledomain.FileResult) error {
	if !lifecycledomain.ValidFileResultBatchSize(len(items)) {
		return lifecycledomain.ErrValidation
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var operation string
	var total int
	if err = tx.QueryRowContext(ctx, "SELECT r.operation,r.total_files FROM lifecycle_file_reports r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND r.attempt=? AND t.attempt=r.attempt AND t.status='running' AND r.complete=0", task, attempt).Scan(&operation, &total); errors.Is(err, sql.ErrNoRows) {
		return lifecycledomain.ErrConflict
	} else if err != nil {
		return err
	}
	statement, err := tx.PrepareContext(ctx, "INSERT INTO lifecycle_file_results(task_id,position,entry_id,state,result_json) VALUES(?,?,?,?,?)")
	if err != nil {
		return err
	}
	defer statement.Close()
	completed := 0
	for _, item := range items {
		if !lifecycledomain.ValidFileResult(item, operation, total) {
			return lifecycledomain.ErrValidation
		}
		data, err := json.Marshal(item)
		if err != nil {
			return err
		}
		if _, err = statement.ExecContext(ctx, task, item.Position, item.EntryID, item.State, string(data)); err != nil {
			return err
		}
		if item.State != "failed" {
			completed++
		}
	}
	if _, err = tx.ExecContext(ctx, "UPDATE tasks SET completed_count=completed_count+? WHERE id=?", completed, task); err != nil {
		return err
	}
	return tx.Commit()
}

func completeFileResults(ctx context.Context, tx *sql.Tx, task string) error {
	result, err := tx.ExecContext(ctx, `UPDATE lifecycle_file_reports SET complete=1 WHERE task_id=? AND attempt=(SELECT attempt FROM tasks WHERE id=?)
 AND total_files=(SELECT COUNT(*) FROM lifecycle_file_results WHERE task_id=?)
 AND NOT EXISTS(SELECT 1 FROM lifecycle_file_results WHERE task_id=? AND state='failed')`, task, task, task, task)
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
	return nil
}

func (repo *repository) FileResults(ctx context.Context, task string, page, size int) (lifecycledomain.FileResultPage, error) {
	value := lifecycledomain.FileResultPage{TaskID: task, Page: page, PageSize: size, Items: []lifecycledomain.FileResult{}}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return value, err
	}
	defer tx.Rollback()
	if err = tx.QueryRowContext(ctx, "SELECT t.status,t.attempt,r.operation FROM tasks t JOIN lifecycle_runs r ON r.task_id=t.id WHERE t.id=?", task).Scan(&value.TaskStatus, &value.TaskAttempt, &value.Operation); errors.Is(err, sql.ErrNoRows) {
		return value, lifecycledomain.ErrNotFound
	} else if err != nil {
		return value, err
	}
	err = tx.QueryRowContext(ctx, "SELECT attempt,total_files,complete FROM lifecycle_file_reports WHERE task_id=?", task).Scan(&value.Attempt, &value.TotalFiles, &value.Complete)
	if errors.Is(err, sql.ErrNoRows) {
		return value, tx.Commit()
	} else if err != nil {
		return value, err
	}
	value.Recorded = true
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*),COALESCE(SUM(state<>'failed'),0),COALESCE(SUM(state='failed'),0) FROM lifecycle_file_results WHERE task_id=?", task).Scan(&value.Total, &value.CompletedFiles, &value.FailedFiles); err != nil {
		return value, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT result_json FROM lifecycle_file_results WHERE task_id=? ORDER BY position LIMIT ? OFFSET ?", task, size, (page-1)*size)
	if err != nil {
		return value, err
	}
	for rows.Next() {
		var data string
		var item lifecycledomain.FileResult
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
