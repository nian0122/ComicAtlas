package sqlite

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
	"time"
)

type repository struct{ database *persistence.Database }

func New(database *persistence.Database) *repository { return &repository{database: database} }

var _ mediaports.LQRepository = (*repository)(nil)

func (repo *repository) Scope(ctx context.Context, kind, id string) (mediadomain.LQScope, error) {
	result := mediadomain.LQScope{Type: kind, ID: id}
	query := "SELECT id,title FROM active_comics AS comics WHERE id=?"
	if kind == "chapter" {
		query = "SELECT comic_id,title FROM active_chapters AS chapters WHERE id=?"
	}
	err := repo.database.Reader.QueryRowContext(ctx, query, id).Scan(&result.ComicID, &result.Title)
	if errors.Is(err, sql.ErrNoRows) {
		err = mediadomain.ErrLQNotFound
	}
	return result, err
}

func scopeWhere(scope mediadomain.LQScope) (string, []any) {
	if scope.Type == "chapter" {
		return "ch.id=? AND ch.comic_id=?", []any{scope.ID, scope.ComicID}
	}
	return "ch.comic_id=?", []any{scope.ComicID}
}

func (repo *repository) Admission(scope mediadomain.LQScope) jobsports.Admission {
	return func(taskID string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			var active, incomplete int
			if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE type IN ('hq_release','archive_export','video_transcode','media_upload','metadata_refresh') AND comic_id=? AND status IN ('queued','running','cancelling')", scope.ComicID).Scan(&active); err != nil {
				return err
			}
			if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND m.hq_state='releasing'", scope.ComicID).Scan(&incomplete); err != nil {
				return err
			}
			if mediadomain.HQBlocksLQ(active, incomplete) {
				return mediadomain.ErrLQConflict
			}
			if err := blockPreparedVideo(ctx, tx, scope.ComicID, ""); err != nil {
				return err
			}
			if err := blockUnfinishedUpload(ctx, tx, scope.ComicID, ""); err != nil {
				return err
			}
			where, args := scopeWhere(scope)
			var exists int
			if scope.Type == "chapter" {
				if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM active_chapters AS chapters WHERE id=? AND comic_id=?", scope.ID, scope.ComicID).Scan(&exists); err != nil {
					return err
				}
			} else {
				if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM active_comics AS comics WHERE id=?", scope.ID).Scan(&exists); err != nil {
					return err
				}
			}
			if exists != 1 {
				return mediadomain.ErrLQNotFound
			}
			var count int
			if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM (SELECT m.id FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.media_type='image' AND "+where+" LIMIT ?)", append(args, mediadomain.MaxLQItems+1)...).Scan(&count); err != nil {
				return err
			}
			if err := mediadomain.ValidateLQSelection(count); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO image_lq_items(task_id,media_id,position,source_path,source_sha256)
		 SELECT ?,m.id,row_number() OVER (ORDER BY ch.global_order,m.page_number,m.id),m.relative_path,m.sha256
		 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.media_type='image' AND `+where, append([]any{taskID}, args...)...); err != nil {
				return err
			}
			_, err := tx.ExecContext(ctx, `INSERT INTO image_lq_latest(media_id,task_id) SELECT media_id,task_id FROM image_lq_items WHERE task_id=?
		 ON CONFLICT(media_id) DO UPDATE SET task_id=excluded.task_id`, taskID)
			return err
		}
	}
}

func (repo *repository) Begin(ctx context.Context, id string) error {
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE image_lq_items SET status='pending',error_code=NULL WHERE task_id=? AND status='failed'", id)
	return err
}
func (repo *repository) Batch(ctx context.Context, id string, after int) ([]mediadomain.LQItem, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT media_id,position,status,error_code,source_path,source_sha256 FROM image_lq_items WHERE task_id=? AND position>? AND status='pending' ORDER BY position LIMIT 64", id, after)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []mediadomain.LQItem{}
	for rows.Next() {
		var item mediadomain.LQItem
		if err = rows.Scan(&item.MediaID, &item.Position, &item.Status, &item.ErrorCode, &item.SourcePath, &item.SourceSHA256); err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}
func (repo *repository) Progress(ctx context.Context, id string) (mediadomain.LQProgress, error) {
	var result mediadomain.LQProgress
	err := repo.database.Reader.QueryRowContext(ctx, `SELECT COUNT(*),COALESCE(SUM(status<>'pending'),0),COALESCE(SUM(status='failed'),0),
	 COALESCE((SELECT error_code FROM image_lq_items WHERE task_id=? AND status='failed' ORDER BY position LIMIT 1),'') FROM image_lq_items WHERE task_id=?`, id, id).Scan(&result.Total, &result.Completed, &result.Failed, &result.FirstError)
	return result, err
}

func (repo *repository) Current(ctx context.Context, id string) (*mediadomain.LQRecord, string, error) {
	var record mediadomain.LQRecord
	var source string
	var path *string
	var storedSHA, hash, taskID *string
	var size *int64
	var width, height, edge, quality *int
	var valid *bool
	var origin, reference, originalState *string
	err := repo.database.Reader.QueryRowContext(ctx, `SELECT m.sha256,l.relative_path,COALESCE(l.source_sha256,''),l.sha256,COALESCE(l.task_id,''),l.size,l.width,l.height,l.max_long_edge,l.quality,l.valid,l.origin,l.source_reference,l.original_state
	 FROM active_media m LEFT JOIN image_lq l ON l.media_id=m.id WHERE m.id=? AND m.media_type='image'`, id).Scan(&source, &path, &storedSHA, &hash, &taskID, &size, &width, &height, &edge, &quality, &valid, &origin, &reference, &originalState)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, "", mediadomain.ErrLQNotFound
	}
	if err != nil {
		return nil, "", err
	}
	if path == nil {
		return nil, source, nil
	}
	record = mediadomain.LQRecord{MediaID: id, Path: *path, SourceSHA256: *storedSHA, SHA256: *hash, TaskID: *taskID, Size: *size, Width: *width, Height: *height, Valid: *valid}
	if edge != nil && quality != nil {
		record.Spec = &mediadomain.ImageSpec{MaxLongEdge: *edge, Quality: *quality}
	}
	if origin != nil && *origin != "generated" {
		record.Provenance = &mediadomain.LQProvenance{Origin: *origin}
		if originalState != nil {
			record.Provenance.OriginalState = *originalState
		}
		if reference != nil {
			record.Provenance.ReferenceSHA256 = *reference
		}
	}
	return &record, source, nil
}

// Record 核对原件路径及摘要，在同一事务内写入新测得的摘要、产物与逐项结果。
func (repo *repository) Record(ctx context.Context, taskID string, item mediadomain.LQItem, record *mediadomain.LQRecord, code string) error {
	if err := mediadomain.ValidateLQResult(item.Status, record != nil, code); err != nil {
		return err
	}
	if item.Status == "generated" && (record == nil || record.Spec == nil || mediadomain.ValidateImageSpec(*record.Spec) != nil) {
		return mediadomain.ErrLQValidation
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var status string
	if err = tx.QueryRowContext(ctx, "SELECT status FROM tasks WHERE id=?", taskID).Scan(&status); err != nil {
		return err
	}
	if !mediadomain.CanRecordLQ(status) {
		return mediadomain.ErrLQConflict
	}
	var failure any = code
	if record != nil {
		var source, sourcePath string
		if err = tx.QueryRowContext(ctx, "SELECT sha256,relative_path FROM active_media AS media WHERE id=? AND media_type='image'", item.MediaID).Scan(&source, &sourcePath); err != nil {
			return mediadomain.ErrLQConflict
		}
		generated := item.Status == "generated"
		if sourcePath != item.SourcePath || !mediadomain.LQSourceMayBeRecorded(item.SourceSHA256, source, record.SourceSHA256, generated) {
			return mediadomain.ErrLQConflict
		}
		if generated {
			if source == "" {
				if _, err = tx.ExecContext(ctx, "UPDATE media SET sha256=? WHERE id=?", record.SourceSHA256, item.MediaID); err != nil {
					return err
				}
			}
			if _, err = tx.ExecContext(ctx, "INSERT OR IGNORE INTO image_lq_garbage SELECT relative_path FROM image_lq WHERE media_id=? AND relative_path<>?", item.MediaID, record.Path); err != nil {
				return err
			}
			if _, err = tx.ExecContext(ctx, `INSERT INTO image_lq(media_id,source_sha256,relative_path,sha256,size,width,height,max_long_edge,quality,task_id,updated_at,valid)
			 VALUES(?,?,?,?,?,?,?,?,?,?,?,1) ON CONFLICT(media_id) DO UPDATE SET source_sha256=excluded.source_sha256,relative_path=excluded.relative_path,sha256=excluded.sha256,size=excluded.size,width=excluded.width,height=excluded.height,max_long_edge=excluded.max_long_edge,quality=excluded.quality,task_id=excluded.task_id,updated_at=excluded.updated_at,valid=1,origin='generated',source_reference=NULL,original_state=NULL`, item.MediaID, record.SourceSHA256, record.Path, record.SHA256, record.Size, record.Width, record.Height, record.Spec.MaxLongEdge, record.Spec.Quality, taskID, time.Now().UTC().Format(jobsdomain.TimestampFormat)); err != nil {
				return err
			}
		}
		failure = nil
	}
	result, err := tx.ExecContext(ctx, "UPDATE image_lq_items SET status=?,error_code=? WHERE task_id=? AND media_id=? AND status='pending'", item.Status, failure, taskID, item.MediaID)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return mediadomain.ErrLQConflict
	}
	return tx.Commit()
}

func (repo *repository) Completion(id string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var pending int
		if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM image_lq_items WHERE task_id=? AND status IN ('pending','failed')", id).Scan(&pending); err != nil {
			return err
		}
		if pending != 0 {
			return mediadomain.ErrLQConflict
		}
		return nil
	}
}
func (repo *repository) Items(ctx context.Context, id string, page, size int) (mediadomain.LQItemPage, error) {
	result := mediadomain.LQItemPage{Items: []mediadomain.LQItem{}, Page: page, PageSize: size}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	var kind string
	if err = tx.QueryRowContext(ctx, "SELECT type FROM tasks WHERE id=?", id).Scan(&kind); errors.Is(err, sql.ErrNoRows) {
		return result, mediadomain.ErrLQNotFound
	} else if err != nil {
		return result, err
	}
	if kind != "image_lq" {
		return result, mediadomain.ErrLQValidation
	}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM image_lq_items WHERE task_id=?", id).Scan(&result.Total); err != nil {
		return result, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT media_id,position,status,error_code FROM image_lq_items WHERE task_id=? ORDER BY position LIMIT ? OFFSET ?", id, size, (page-1)*size)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var item mediadomain.LQItem
		if err = rows.Scan(&item.MediaID, &item.Position, &item.Status, &item.ErrorCode); err != nil {
			rows.Close()
			return result, err
		}
		if item.ErrorCode != nil {
			normalized := jobsdomain.WithDiagnostics(jobsdomain.Task{Status: "failed", ErrorCode: item.ErrorCode})
			item.ErrorCode = normalized.ErrorCode
		}
		result.Items = append(result.Items, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	return result, tx.Commit()
}
func (repo *repository) Invalidate(ctx context.Context, record mediadomain.LQRecord) error {
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE image_lq SET valid=0 WHERE media_id=? AND relative_path=? AND sha256=?", record.MediaID, record.Path, record.SHA256)
	return err
}
func (repo *repository) Garbage(ctx context.Context) ([]string, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT relative_path FROM image_lq_garbage WHERE relative_path NOT IN (SELECT relative_path FROM image_lq) LIMIT 64")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []string{}
	for rows.Next() {
		var path string
		if err = rows.Scan(&path); err != nil {
			return nil, err
		}
		result = append(result, path)
	}
	return result, rows.Err()
}
func (repo *repository) Collected(ctx context.Context, path string) error {
	_, err := repo.database.Writer.ExecContext(ctx, "DELETE FROM image_lq_garbage WHERE relative_path=?", path)
	return err
}
