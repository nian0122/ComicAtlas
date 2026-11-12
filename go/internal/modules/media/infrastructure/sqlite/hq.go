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
)

var _ mediaports.HQRepository = (*repository)(nil)

const hqCurrentColumns = "m.hq_state,m.relative_path,m.sha256,m.size,COALESCE(l.source_sha256,''),l.relative_path,l.sha256,l.size,l.valid,COALESCE(l.task_id,''),l.origin,l.source_reference,l.original_state"

func scanHQCurrent(scanner interface{ Scan(...any) error }) (mediadomain.HQCurrent, error) {
	var current mediadomain.HQCurrent
	var source, path, hash *string
	var size *int64
	var valid *bool
	var task string
	var origin, reference, originalState *string
	err := scanner.Scan(&current.State, &current.SourcePath, &current.SourceSHA256, &current.Size, &source, &path, &hash, &size, &valid, &task, &origin, &reference, &originalState)
	if err == nil && path != nil {
		current.LQ = &mediadomain.LQRecord{SourceSHA256: *source, Path: *path, SHA256: *hash, Size: *size, Valid: *valid, TaskID: task}
		if origin != nil && *origin != "generated" {
			current.LQ.Provenance = &mediadomain.LQProvenance{Origin: *origin}
			if reference != nil {
				current.LQ.Provenance.ReferenceSHA256 = *reference
			}
			if originalState != nil {
				current.LQ.Provenance.OriginalState = *originalState
			}
		}
	}
	if errors.Is(err, sql.ErrNoRows) {
		err = mediadomain.ErrLQNotFound
	}
	return current, err
}
func (repo *repository) HQCurrent(ctx context.Context, id string) (mediadomain.HQCurrent, error) {
	return scanHQCurrent(repo.database.Reader.QueryRowContext(ctx, "SELECT "+hqCurrentColumns+" FROM active_media m LEFT JOIN image_lq l ON l.media_id=m.id WHERE m.id=? AND m.media_type='image'", id))
}
func hqPreview(ctx context.Context, tx *sql.Tx, scope mediadomain.LQScope, excludeTask string) (mediadomain.HQReleasePreview, error) {
	result := mediadomain.HQReleasePreview{ScopeType: scope.Type, ScopeID: scope.ID, Blockers: []string{}}
	where, args := scopeWhere(scope)
	var exists int
	query := "SELECT COUNT(*) FROM active_comics AS comics WHERE id=?"
	if scope.Type == "chapter" {
		query = "SELECT COUNT(*) FROM active_chapters AS chapters WHERE id=?"
	}
	if err := tx.QueryRowContext(ctx, query, scope.ID).Scan(&exists); err != nil {
		return result, err
	}
	if exists != 1 {
		return result, mediadomain.ErrLQNotFound
	}
	rows, err := tx.QueryContext(ctx, "SELECT "+hqCurrentColumns+" FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id LEFT JOIN image_lq l ON l.media_id=m.id WHERE m.media_type='image' AND "+where+" ORDER BY ch.global_order,m.page_number,m.id LIMIT ?", append(args, mediadomain.MaxLQItems+1)...)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		current, err := scanHQCurrent(rows)
		if err != nil {
			rows.Close()
			return result, err
		}
		mediadomain.AddHQPreview(&result, current)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	var active, incomplete int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE comic_id=? AND type IN ('image_lq','hq_release','archive_export','video_transcode','media_upload','metadata_refresh') AND status IN ('queued','running','cancelling') AND id<>?", scope.ComicID, excludeTask).Scan(&active); err != nil {
		return result, err
	}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND m.hq_state='releasing'", scope.ComicID).Scan(&incomplete); err != nil {
		return result, err
	}
	mediadomain.FinishHQPreview(&result, active > 0, incomplete > 0)
	if err := blockUnfinishedUpload(ctx, tx, scope.ComicID, excludeTask); err != nil {
		if !errors.Is(err, mediadomain.ErrLQConflict) {
			return result, err
		}
		result.Allowed = false
		result.Blockers = append(result.Blockers, "INCOMPLETE_UPLOAD")
	}
	if err := blockPreparedVideo(ctx, tx, scope.ComicID, excludeTask); err != nil {
		if !errors.Is(err, mediadomain.ErrLQConflict) {
			return result, err
		}
		result.Allowed = false
		result.Blockers = append(result.Blockers, "INCOMPLETE_TRANSCODE")
	}
	return result, nil
}
func (repo *repository) HQPreview(ctx context.Context, scope mediadomain.LQScope) (mediadomain.HQReleasePreview, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return mediadomain.HQReleasePreview{}, err
	}
	defer tx.Rollback()
	preview, err := hqPreview(ctx, tx, scope, "")
	if err != nil {
		return preview, err
	}
	return preview, tx.Commit()
}
func (repo *repository) HQAdmission(scope mediadomain.LQScope) jobsports.Admission {
	return func(taskID string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			preview, err := hqPreview(ctx, tx, scope, taskID)
			if err != nil {
				return err
			}
			if !preview.Allowed {
				return mediadomain.ErrLQConflict
			}
			where, args := scopeWhere(scope)
			_, err = tx.ExecContext(ctx, `INSERT INTO hq_release_items(task_id,media_id,position,source_path,source_sha256,original_bytes,lq_path,lq_sha256,lq_bytes)
		 SELECT ?,m.id,row_number() OVER(ORDER BY ch.global_order,m.page_number,m.id),m.relative_path,m.sha256,m.size,l.relative_path,l.sha256,l.size
		 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id JOIN image_lq l ON l.media_id=m.id WHERE m.media_type='image' AND m.hq_state='available' AND `+where, append([]any{taskID}, args...)...)
			return err
		}
	}
}
func (repo *repository) HQBegin(ctx context.Context, id string) error {
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE hq_release_items SET status=CASE WHEN status='failed' THEN 'pending' ELSE status END,error_code=NULL WHERE task_id=? AND status<>'released'", id)
	return err
}

const hqItemColumns = "media_id,position,status,original_bytes,outcome,error_code,source_path,source_sha256,lq_path,lq_sha256,lq_bytes"

func scanHQItem(scanner interface{ Scan(...any) error }) (mediadomain.HQReleaseItem, error) {
	var item mediadomain.HQReleaseItem
	err := scanner.Scan(&item.MediaID, &item.Position, &item.Status, &item.OriginalBytes, &item.Outcome, &item.ErrorCode, &item.SourcePath, &item.SourceSHA256, &item.LQ.Path, &item.LQ.SHA256, &item.LQ.Size)
	item.LQ.SourceSHA256, item.LQ.Valid = item.SourceSHA256, true
	if item.ErrorCode != nil {
		task := jobsdomain.WithDiagnostics(jobsdomain.Task{Status: "failed", ErrorCode: item.ErrorCode})
		item.ErrorCode = task.ErrorCode
	}
	return item, err
}
func (repo *repository) HQBatch(ctx context.Context, id string, after int) ([]mediadomain.HQReleaseItem, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT "+hqItemColumns+" FROM hq_release_items WHERE task_id=? AND position>? AND status<>'released' ORDER BY position LIMIT 64", id, after)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []mediadomain.HQReleaseItem{}
	for rows.Next() {
		item, err := scanHQItem(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}
func (repo *repository) hqWrite(ctx context.Context, id string, item mediadomain.HQReleaseItem, outcome string, prepare bool) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	current, err := scanHQCurrent(tx.QueryRowContext(ctx, "SELECT "+hqCurrentColumns+" FROM active_media m LEFT JOIN image_lq l ON l.media_id=m.id WHERE m.id=?", item.MediaID))
	if err != nil {
		return err
	}
	var state string
	if err = tx.QueryRowContext(ctx, "SELECT status FROM tasks WHERE id=?", id).Scan(&state); err != nil {
		return err
	}
	status, hqState, err := mediadomain.HQWriteState(item, current, state, prepare, outcome)
	if err != nil {
		return err
	}
	var result any
	if !prepare {
		result = outcome
	}
	_, err = tx.ExecContext(ctx, "UPDATE media SET hq_state=? WHERE id=?", hqState, item.MediaID)
	if err != nil {
		return err
	}
	updated, err := tx.ExecContext(ctx, "UPDATE hq_release_items SET status=?,outcome=?,error_code=NULL WHERE task_id=? AND media_id=? AND status IN ('pending','prepared')", status, result, id, item.MediaID)
	if err != nil {
		return err
	}
	count, err := updated.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return mediadomain.ErrLQConflict
	}
	return tx.Commit()
}
func (repo *repository) HQPrepare(ctx context.Context, id string, item mediadomain.HQReleaseItem) error {
	return repo.hqWrite(ctx, id, item, "", true)
}
func (repo *repository) HQRecord(ctx context.Context, id string, item mediadomain.HQReleaseItem, outcome string) error {
	return repo.hqWrite(ctx, id, item, outcome, false)
}
func (repo *repository) HQFail(ctx context.Context, id string, item mediadomain.HQReleaseItem, code string) error {
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE hq_release_items SET status=CASE WHEN status='prepared' THEN status ELSE 'failed' END,error_code=? WHERE task_id=? AND media_id=? AND status<>'released'", code, id, item.MediaID)
	return err
}
func (repo *repository) HQProgress(ctx context.Context, id string) (int, int, error) {
	var total, done int
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT COUNT(*),COALESCE(SUM(status='released'),0) FROM hq_release_items WHERE task_id=?", id).Scan(&total, &done)
	return total, done, err
}
func (repo *repository) HQCompletion(id string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var pending int
		if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM hq_release_items WHERE task_id=? AND status<>'released'", id).Scan(&pending); err != nil {
			return err
		}
		if pending != 0 {
			return mediadomain.ErrLQConflict
		}
		return nil
	}
}
func (repo *repository) HQItems(ctx context.Context, id string, page, size int) (mediadomain.HQReleasePage, error) {
	result := mediadomain.HQReleasePage{Items: []mediadomain.HQReleaseItem{}, Page: page, PageSize: size}
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
	if kind != "hq_release" {
		return result, mediadomain.ErrLQValidation
	}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM hq_release_items WHERE task_id=?", id).Scan(&result.Total); err != nil {
		return result, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT media_id,position,status,original_bytes,outcome,error_code FROM hq_release_items WHERE task_id=? ORDER BY position LIMIT ? OFFSET ?", id, size, (page-1)*size)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var item mediadomain.HQReleaseItem
		if err := rows.Scan(&item.MediaID, &item.Position, &item.Status, &item.OriginalBytes, &item.Outcome, &item.ErrorCode); err != nil {
			rows.Close()
			return result, err
		}
		if item.ErrorCode != nil {
			task := jobsdomain.WithDiagnostics(jobsdomain.Task{Status: "failed", ErrorCode: item.ErrorCode})
			item.ErrorCode = task.ErrorCode
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
