package sqlite

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"

	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
)

func (store *sqliteRepository) CreateImportBatch(ctx context.Context, inputs []jobsdomain.PreparedImport, key, hash string, admission jobsports.BatchAdmission) (jobsdomain.TaskBatch, error) {
	result := jobsdomain.TaskBatch{Items: []jobsdomain.BatchTask{}}
	tx, err := store.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	if key != "" {
		var existing string
		err = tx.QueryRowContext(ctx, "SELECT id,request_hash FROM task_batches WHERE idempotency_key=?", key).Scan(&result.ID, &existing)
		if err == nil {
			if existing != hash {
				return result, jobsdomain.ErrConflict
			}
			if err = loadImportBatch(ctx, tx, &result); err != nil {
				return result, err
			}
			return result, tx.Commit()
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return result, err
		}
	}
	var token [16]byte
	if _, err = rand.Read(token[:]); err != nil {
		return result, err
	}
	result.ID = "batch_" + hex.EncodeToString(token[:])
	for _, input := range inputs {
		if err = blockImportSources(ctx, tx, input.Resource.SourcePath, ""); err != nil {
			return result, err
		}
		if err = blockReservedImportSources(ctx, tx, input.Resource.SourcePath, "", "", 0); err != nil {
			return result, err
		}

	}
	var idempotency any
	if key != "" {
		idempotency = key
	}
	parent, err := store.createInTx(ctx, tx, jobsdomain.ResourceTask{Type: "import_batch", TargetType: "import_batch", TargetID: result.ID, Title: "批量导入", Payload: "{}"}, key, hash, false, nil)
	if err != nil {
		return result, err
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO task_batches(id,idempotency_key,request_hash,parent_task_id) VALUES(?,?,?,?)", result.ID, idempotency, hash, parent); err != nil {
		return result, err
	}
	ids := make([]string, 0, len(inputs))
	for index, input := range inputs {
		if _, err = tx.ExecContext(ctx, `INSERT INTO import_batch_dispatch_items(batch_id,position,reference_id,source_path,prepared_json) VALUES(?,?,?,?,?)`, result.ID, index+1, input.ReferenceID, input.Resource.SourcePath, marshalPrepared(input)); err != nil {
			return result, err
		}
		ids = append(ids, input.ReferenceID)
		result.Items = append(result.Items, jobsdomain.BatchTask{ReferenceID: input.ReferenceID, Status: "pending"})
	}
	if _, err = tx.ExecContext(ctx, "UPDATE tasks SET total_count=? WHERE id=?", len(inputs), parent); err != nil {
		return result, err
	}
	if admission != nil {
		if err = admission(ids)(ctx, tx); err != nil {
			return result, err
		}
	}
	result.TaskID, result.Status, result.Total = parent, jobsdomain.TaskStatusQueued, len(inputs)
	return result, tx.Commit()
}

func marshalPrepared(input jobsdomain.PreparedImport) string {
	data, _ := json.Marshal(input)
	return string(data)
}

func loadImportBatch(ctx context.Context, tx *sql.Tx, result *jobsdomain.TaskBatch) error {
	var parent sql.NullString
	if err := tx.QueryRowContext(ctx, "SELECT parent_task_id FROM task_batches WHERE id=?", result.ID).Scan(&parent); err != nil {
		return err
	}
	if parent.Valid {
		result.TaskID = parent.String
		if err := tx.QueryRowContext(ctx, "SELECT status,COALESCE(total_count,0),completed_count FROM tasks WHERE id=?", parent.String).Scan(&result.Status, &result.Total, &result.Completed); err != nil {
			return err
		}
		rows, err := tx.QueryContext(ctx, "SELECT reference_id,task_id,status,error_code FROM import_batch_dispatch_items WHERE batch_id=? ORDER BY position", result.ID)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var item jobsdomain.BatchTask
			var taskID sql.NullString
			if err = rows.Scan(&item.ReferenceID, &taskID, &item.Status, &item.ErrorCode); err != nil {
				return err
			}
			item.TaskID = taskID.String
			result.Items = append(result.Items, item)
		}
		return rows.Err()
	}
	rows, err := tx.QueryContext(ctx, `SELECT i.reference_id,i.task_id,t.status,t.error_code FROM task_batch_items i JOIN tasks t ON t.id=i.task_id WHERE i.batch_id=? ORDER BY i.position`, result.ID)
	if err != nil {
		return err
	}
	defer rows.Close()
	legacyStatus := jobsdomain.TaskStatusQueued
	legacyFailed, legacyCancelled := false, false
	for rows.Next() {
		var item jobsdomain.BatchTask
		var taskID, taskStatus string
		if err = rows.Scan(&item.ReferenceID, &taskID, &taskStatus, &item.ErrorCode); err != nil {
			return err
		}
		item.TaskID = taskID
		item.Status = taskStatus
		if taskStatus == jobsdomain.TaskStatusQueued || taskStatus == jobsdomain.TaskStatusRunning || taskStatus == jobsdomain.TaskStatusCancelling {
			item.Status = "dispatched"
		}
		result.Items = append(result.Items, item)
		result.Total++
		if taskStatus == jobsdomain.TaskStatusSucceeded || taskStatus == jobsdomain.TaskStatusFailed || taskStatus == jobsdomain.TaskStatusCancelled {
			result.Completed++
		}
		if taskStatus == jobsdomain.TaskStatusRunning || taskStatus == jobsdomain.TaskStatusCancelling {
			if taskStatus == jobsdomain.TaskStatusCancelling || legacyStatus != jobsdomain.TaskStatusCancelling {
				legacyStatus = taskStatus
			}
		}
		legacyFailed = legacyFailed || taskStatus == jobsdomain.TaskStatusFailed
		legacyCancelled = legacyCancelled || taskStatus == jobsdomain.TaskStatusCancelled
	}
	if result.Total > 0 && result.Completed == result.Total && legacyStatus != jobsdomain.TaskStatusCancelling {
		legacyStatus = jobsdomain.TaskStatusSucceeded
		if legacyFailed {
			legacyStatus = jobsdomain.TaskStatusFailed
		} else if legacyCancelled {
			legacyStatus = jobsdomain.TaskStatusCancelled
		}
	}
	result.Status = legacyStatus
	return rows.Err()
}

func (store *sqliteRepository) GetImportBatch(ctx context.Context, id string) (jobsdomain.TaskBatch, error) {
	result := jobsdomain.TaskBatch{ID: id, Items: []jobsdomain.BatchTask{}}
	tx, err := store.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	if err = loadImportBatch(ctx, tx, &result); errors.Is(err, sql.ErrNoRows) {
		return result, jobsdomain.ErrNotFound
	} else if err != nil {
		return result, err
	}
	if result.TaskID == "" && len(result.Items) == 0 {
		return result, jobsdomain.ErrNotFound
	}
	return result, tx.Commit()
}
