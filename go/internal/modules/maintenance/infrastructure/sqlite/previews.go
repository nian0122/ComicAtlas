package sqlite

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

func (repo *repository) CreatePreview(ctx context.Context, id, request, hash string, targets []maintenancedomain.Item, now time.Time) (maintenancedomain.Preview, error) {
	var input maintenancedomain.Request
	if err := json.Unmarshal([]byte(request), &input); err != nil {
		return maintenancedomain.Preview{}, err
	}
	if err := maintenancedomain.ValidateDangerousConfirmation(input.Operation, hash, hash); err != nil {
		return maintenancedomain.Preview{}, err
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return maintenancedomain.Preview{}, err
	}
	defer tx.Rollback()
	stamp := now.Format(jobsdomain.TimestampFormat)
	if _, err = tx.ExecContext(ctx, "DELETE FROM batch_previews WHERE task_id IS NULL AND expires_at<=?", stamp); err != nil {
		return maintenancedomain.Preview{}, err
	}
	var count int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM batch_previews WHERE task_id IS NULL").Scan(&count); err != nil {
		return maintenancedomain.Preview{}, err
	}
	if err = maintenancedomain.ValidatePreviewCapacity(count); err != nil {
		return maintenancedomain.Preview{}, err
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO batch_previews(id,request_json,created_at,expires_at) VALUES(?,?,?,?)", id, request, stamp, maintenancedomain.PreviewExpiresAt(input.Operation, now)); err != nil {
		return maintenancedomain.Preview{}, err
	}
	statement, err := tx.PrepareContext(ctx, "INSERT INTO batch_preview_items(preview_id,position,comic_id,title,version,status,error_code,scope_revision,image_count,video_count,hq_json,entry_id,entry_stamp,target_type,target_id,dependencies_json,purge_json,covered_by,target_stamp,chapter_id,target_files) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
	if err != nil {
		return maintenancedomain.Preview{}, err
	}
	defer statement.Close()
	summary := maintenancedomain.HQSummary{}
	for _, item := range targets {
		var hq, purge any
		if item.Purge != nil {
			data, marshalErr := json.Marshal(item.Purge)
			if marshalErr != nil {
				return maintenancedomain.Preview{}, marshalErr
			}
			purge = string(data)
		}
		if item.HQ != nil {
			data, marshalErr := json.Marshal(item.HQ)
			if marshalErr != nil {
				return maintenancedomain.Preview{}, marshalErr
			}
			hq = string(data)
		}
		maintenancedomain.AddHQSummary(&summary, item)
		deps := item.Dependencies
		if deps == nil {
			deps = []int{}
		}
		dependencyData, _ := json.Marshal(deps)
		if _, err = statement.ExecContext(ctx, id, item.Position, item.ComicID, item.Title, item.Version, item.Status, item.ErrorCode, item.ScopeRevision, item.ImageCount, item.VideoCount, hq, item.EntryID, item.EntryStamp, item.TargetType, item.TargetID, string(dependencyData), purge, item.CoveredBy, item.TargetStamp, item.ChapterID, item.TargetFiles); err != nil {
			return maintenancedomain.Preview{}, err
		}
	}
	if input.Operation == "trash_purge" {
		data, marshalErr := json.Marshal(maintenancedomain.SummarizePurge(targets))
		if marshalErr != nil {
			return maintenancedomain.Preview{}, marshalErr
		}
		if _, err = tx.ExecContext(ctx, "INSERT INTO batch_purge_summaries VALUES(?,?)", id, string(data)); err != nil {
			return maintenancedomain.Preview{}, err
		}
	}
	if maintenancedomain.RequiresConfirmation(input.Operation) {
		if _, err = tx.ExecContext(ctx, "INSERT INTO batch_confirmations(preview_id,token_hash,image_count,original_bytes) VALUES(?,?,?,?)", id, hash, summary.Images, summary.OriginalBytes); err != nil {
			return maintenancedomain.Preview{}, err
		}
	}
	if err = tx.Commit(); err != nil {
		return maintenancedomain.Preview{}, err
	}
	value, err := repo.Preview(ctx, id)
	return value.Preview, err
}
func readPreview(ctx context.Context, tx *sql.Tx, id string) (maintenancedomain.Record, error) {
	var value maintenancedomain.Record
	preview := &value.Preview
	var hq maintenancedomain.HQSummary
	err := tx.QueryRowContext(ctx, `SELECT p.id,o.options_json,p.created_at,p.expires_at,p.task_id,t.status,p.completed_at,
 c.total_count,c.blocked_count,c.pending_count,c.succeeded_count,c.failed_count,c.active_count,c.cancelled_count,
 COALESCE(confirm.token_hash,''),COALESCE(confirm.image_count,0),COALESCE(confirm.original_bytes,0)
 FROM batch_previews p JOIN batch_preview_counts c ON c.preview_id=p.id JOIN batch_preview_options o ON o.preview_id=p.id LEFT JOIN tasks t ON t.id=p.task_id LEFT JOIN batch_confirmations confirm ON confirm.preview_id=p.id WHERE p.id=?`, id).Scan(&preview.ID, &value.OptionsJSON, &preview.CreatedAt, &preview.ExpiresAt, &preview.TaskID, &preview.TaskStatus, &preview.CompletedAt, &preview.SelectedCount, &preview.BlockedCount, &preview.PendingCount, &preview.SucceededCount, &preview.FailedCount, &preview.ActiveCount, &preview.CancelledCount, &value.ConfirmationHash, &hq.Images, &hq.OriginalBytes)
	if errors.Is(err, sql.ErrNoRows) {
		return value, jobsdomain.ErrNotFound
	}
	if err != nil {
		return value, err
	}
	var request maintenancedomain.BatchOptions
	if err = json.Unmarshal([]byte(value.OptionsJSON), &request); err != nil {
		return value, err
	}
	preview.Operation = request.Operation
	preview.Patch = request.Patch
	preview.LQ = request.LQ
	if request.Operation == "trash_purge" {
		var data string
		if err = tx.QueryRowContext(ctx, "SELECT summary_json FROM batch_purge_summaries WHERE preview_id=?", id).Scan(&data); err != nil {
			return value, err
		}
		if err = json.Unmarshal([]byte(data), &preview.Purge); err != nil {
			return value, err
		}
	}
	if request.Operation == "hq_release" {
		hq.Effect = "delete_hq_keep_lq"
		preview.HQ = &hq
	}
	*preview = maintenancedomain.ProjectPreview(*preview)
	return value, nil
}
func (repo *repository) Preview(ctx context.Context, id string) (maintenancedomain.Record, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return maintenancedomain.Record{}, err
	}
	defer tx.Rollback()
	value, err := readPreview(ctx, tx, id)
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}

const itemColumns = "i.position,i.comic_id,i.title,i.version,i.status,i.error_code,i.updated_version,i.scope_revision,i.image_count,i.video_count,i.child_task_id,i.wait_reason,i.hq_json,i.entry_id,i.entry_stamp,i.target_type,i.target_id,i.dependencies_json,i.purge_json,i.covered_by,(SELECT root.child_task_id FROM batch_preview_items root WHERE root.preview_id=i.preview_id AND root.position=i.covered_by),i.target_stamp,i.chapter_id,i.target_files"

func scanItem(row interface{ Scan(...any) error }) (maintenancedomain.Item, error) {
	var item maintenancedomain.Item
	var hq, purge *string
	var deps string
	err := row.Scan(&item.Position, &item.ComicID, &item.Title, &item.Version, &item.Status, &item.ErrorCode, &item.UpdatedVersion, &item.ScopeRevision, &item.ImageCount, &item.VideoCount, &item.ChildTaskID, &item.WaitReason, &hq, &item.EntryID, &item.EntryStamp, &item.TargetType, &item.TargetID, &deps, &purge, &item.CoveredBy, &item.CoveredTaskID, &item.TargetStamp, &item.ChapterID, &item.TargetFiles)
	if err == nil && purge != nil {
		err = json.Unmarshal([]byte(*purge), &item.Purge)
	}
	if err == nil {
		err = json.Unmarshal([]byte(deps), &item.Dependencies)
	}
	if err == nil && hq != nil {
		err = json.Unmarshal([]byte(*hq), &item.HQ)
	}
	if errors.Is(err, sql.ErrNoRows) {
		err = jobsdomain.ErrNotFound
	}
	return item, err
}
func (repo *repository) Items(ctx context.Context, id string, page, size int) (maintenancedomain.ItemPage, error) {
	value := maintenancedomain.ItemPage{Items: []maintenancedomain.Item{}, Page: page, PageSize: size}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return value, err
	}
	defer tx.Rollback()
	record, err := readPreview(ctx, tx, id)
	if err != nil {
		return value, err
	}
	value.Total = record.Preview.SelectedCount
	rows, err := tx.QueryContext(ctx, "SELECT "+itemColumns+" FROM batch_preview_items i WHERE i.preview_id=? ORDER BY i.position LIMIT ? OFFSET ?", id, size, (page-1)*size)
	if err != nil {
		return value, err
	}
	defer rows.Close()
	for rows.Next() {
		item, err := scanItem(rows)
		if err != nil {
			return value, err
		}
		value.Items = append(value.Items, maintenancedomain.ProjectItem(item, record.Preview.TaskStatus))
	}
	if err = rows.Err(); err != nil {
		return value, err
	}
	rows.Close()
	return value, tx.Commit()
}
