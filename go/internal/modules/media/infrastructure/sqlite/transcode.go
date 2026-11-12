package sqlite

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"

	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

var _ mediaports.TranscodeRepository = (*repository)(nil)

func blockPreparedVideo(ctx context.Context, tx *sql.Tx, comic, exclude string) error {
	var count int
	err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM video_transcode_items i JOIN tasks t ON t.id=i.task_id WHERE t.comic_id=? AND t.id<>? AND i.status='prepared'", comic, exclude).Scan(&count)
	if err != nil {
		return err
	}
	if count > 0 {
		return mediadomain.ErrLQConflict
	}
	return nil
}

func (repo *repository) TranscodeScope(ctx context.Context, kind, id string) (mediadomain.TranscodeScope, error) {
	result := mediadomain.TranscodeScope{Type: kind, ID: id}
	query := "SELECT id,title FROM active_comics AS comics WHERE id=?"
	if kind == "chapter" {
		query = "SELECT comic_id,title FROM active_chapters AS chapters WHERE id=?"
	}
	if kind == "media" {
		query = "SELECT ch.comic_id,m.file_name FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.id=? AND m.media_type='video'"
	}
	err := repo.database.Reader.QueryRowContext(ctx, query, id).Scan(&result.ComicID, &result.Title)
	if errors.Is(err, sql.ErrNoRows) {
		err = mediadomain.ErrLQNotFound
	}
	return result, err
}
func transcodeWhere(scope mediadomain.TranscodeScope) (string, []any) {
	if scope.Type == "media" {
		return "m.id=? AND ch.comic_id=?", []any{scope.ID, scope.ComicID}
	}
	return scopeWhere(mediadomain.LQScope{Type: scope.Type, ID: scope.ID, ComicID: scope.ComicID})
}
func (repo *repository) TranscodeAdmission(scope mediadomain.TranscodeScope) jobsports.Admission {
	return func(id string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			where, args := transcodeWhere(scope)
			var count, active, incomplete int
			if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM (SELECT m.id FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.media_type='video' AND "+where+" LIMIT ?)", append(args, mediadomain.MaxTranscodeItems+1)...).Scan(&count); err != nil {
				return err
			}
			if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE comic_id=? AND id<>? AND type IN ('image_lq','hq_release','archive_export','video_transcode','media_upload','metadata_refresh') AND status IN ('queued','running','cancelling')", scope.ComicID, id).Scan(&active); err != nil {
				return err
			}
			if err := tx.QueryRowContext(ctx, `SELECT (SELECT COUNT(*) FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND m.hq_state='releasing')+(SELECT COUNT(*) FROM video_transcode_items i JOIN tasks t ON t.id=i.task_id WHERE t.comic_id=? AND i.status='prepared')`, scope.ComicID, scope.ComicID).Scan(&incomplete); err != nil {
				return err
			}
			if err := mediadomain.ValidateTranscodeSelection(count, active, incomplete); err != nil {
				return err
			}
			if err := blockUnfinishedUpload(ctx, tx, scope.ComicID, id); err != nil {
				return err
			}
			// 重验范围存在性，避免受理前的读取与删除之间留下空任务。
			var exists int
			query := "SELECT COUNT(*) FROM active_comics AS comics WHERE id=?"
			if scope.Type == "chapter" {
				query = "SELECT COUNT(*) FROM active_chapters AS chapters WHERE id=? AND comic_id=?"
			}
			if scope.Type == "media" {
				query = "SELECT COUNT(*) FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.id=? AND ch.comic_id=? AND m.media_type='video'"
			}
			existsArgs := []any{scope.ID}
			if scope.Type != "comic" {
				existsArgs = append(existsArgs, scope.ComicID)
			}
			if err := tx.QueryRowContext(ctx, query, existsArgs...).Scan(&exists); err != nil {
				return err
			}
			if exists != 1 {
				return mediadomain.ErrLQNotFound
			}
			_, err := tx.ExecContext(ctx, `INSERT INTO video_transcode_items(task_id,media_id,position,source_name,source_path,source_sha256,source_size)
		 SELECT ?,m.id,row_number() OVER(ORDER BY ch.global_order,m.page_number,m.id),m.file_name,m.relative_path,m.sha256,m.size FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.media_type='video' AND `+where, append([]any{id}, args...)...)
			return err
		}
	}
}
func (repo *repository) TranscodeBegin(ctx context.Context, id string) error {
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE video_transcode_items SET status=CASE WHEN status='failed' THEN 'pending' ELSE status END,error_code=NULL WHERE task_id=? AND status IN ('failed','prepared')", id)
	return err
}

const transcodeColumns = "media_id,position,status,error_code,source_name,source_path,source_sha256,source_size,output_json"

func scanTranscode(scanner interface{ Scan(...any) error }) (mediadomain.TranscodeItem, error) {
	var item mediadomain.TranscodeItem
	var output *string
	err := scanner.Scan(&item.MediaID, &item.Position, &item.Status, &item.ErrorCode, &item.SourceName, &item.SourcePath, &item.SourceSHA256, &item.SourceSize, &output)
	if err == nil && output != nil {
		var record mediadomain.TranscodeRecord
		err = json.Unmarshal([]byte(*output), &record)
		item.Output = &record
	}
	return item, err
}
func (repo *repository) TranscodeBatch(ctx context.Context, id string, after int) ([]mediadomain.TranscodeItem, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT "+transcodeColumns+" FROM video_transcode_items WHERE task_id=? AND position>? AND status IN ('pending','prepared') ORDER BY position LIMIT 64", id, after)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []mediadomain.TranscodeItem{}
	for rows.Next() {
		item, err := scanTranscode(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}
func (repo *repository) TranscodePrepare(ctx context.Context, id string, item mediadomain.TranscodeItem, record mediadomain.TranscodeRecord) error {
	if err := mediadomain.ValidateTranscodeRecord(record); err != nil {
		return err
	}
	value, err := json.Marshal(record)
	if err != nil {
		return err
	}
	result, err := repo.database.Writer.ExecContext(ctx, `UPDATE video_transcode_items SET status='prepared',output_json=?,error_code=NULL WHERE task_id=? AND media_id=? AND status='pending' AND EXISTS(SELECT 1 FROM tasks WHERE id=? AND status='running')`, string(value), id, item.MediaID, id)
	return transcodeUpdated(result, err)
}
func transcodeUpdated(result sql.Result, err error) error {
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
	return nil
}
func (repo *repository) TranscodeCommit(ctx context.Context, id string, item mediadomain.TranscodeItem) error {
	if item.Output == nil {
		return mediadomain.ErrTranscodeOutput
	}
	if err := mediadomain.ValidateTranscodeRecord(*item.Output); err != nil {
		return err
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	video, err := json.Marshal(item.Output.Video)
	if err != nil {
		return err
	}
	result, err := tx.ExecContext(ctx, `UPDATE media SET relative_path=?,file_name=?,content_type='video/mp4',size=?,sha256=?,width=?,height=?,video_metadata=? WHERE id=? AND media_type='video' AND hq_state='available' AND relative_path=? AND sha256=? AND size=? AND EXISTS(SELECT 1 FROM tasks WHERE id=? AND status='running') AND EXISTS(SELECT 1 FROM video_transcode_items WHERE task_id=? AND media_id=media.id AND status='prepared' AND output_json=?)`, item.Output.Path, mediadomain.TranscodedName(item.SourceName), item.Output.Size, item.Output.SHA256, item.Output.Video.Width, item.Output.Video.Height, string(video), item.MediaID, item.SourcePath, item.SourceSHA256, item.SourceSize, id, id, mustTranscodeJSON(*item.Output))
	if err = transcodeUpdated(result, err); err != nil {
		return err
	}
	result, err = tx.ExecContext(ctx, "UPDATE video_transcode_items SET status='completed',error_code=NULL WHERE task_id=? AND media_id=? AND status='prepared'", id, item.MediaID)
	if err = transcodeUpdated(result, err); err != nil {
		return err
	}
	return tx.Commit()
}
func mustTranscodeJSON(record mediadomain.TranscodeRecord) string {
	data, _ := json.Marshal(record)
	return string(data)
}
func (repo *repository) TranscodeSkip(ctx context.Context, id string, item mediadomain.TranscodeItem) error {
	result, err := repo.database.Writer.ExecContext(ctx, `UPDATE video_transcode_items SET status='skipped',error_code=NULL WHERE task_id=? AND media_id=? AND status='pending' AND EXISTS(SELECT 1 FROM active_media AS media WHERE id=? AND relative_path=? AND sha256=? AND size=? AND hq_state='available') AND EXISTS(SELECT 1 FROM tasks WHERE id=? AND status='running')`, id, item.MediaID, item.MediaID, item.SourcePath, item.SourceSHA256, item.SourceSize, id)
	return transcodeUpdated(result, err)
}
func (repo *repository) TranscodeFail(ctx context.Context, id string, item mediadomain.TranscodeItem, code string) error {
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE video_transcode_items SET status=CASE WHEN status='prepared' THEN status ELSE 'failed' END,error_code=? WHERE task_id=? AND media_id=? AND status IN ('pending','prepared','failed')", code, id, item.MediaID)
	return err
}
func (repo *repository) TranscodeProgress(ctx context.Context, id string) (int, int, error) {
	var total, done int
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT COUNT(*),COALESCE(SUM(status IN ('completed','skipped')),0) FROM video_transcode_items WHERE task_id=?", id).Scan(&total, &done)
	return total, done, err
}
func (repo *repository) TranscodeCompletion(id string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var pending int
		err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM video_transcode_items WHERE task_id=? AND (status NOT IN ('completed','skipped') OR (status='completed' AND collected=0))", id).Scan(&pending)
		if err != nil {
			return err
		}
		if pending != 0 {
			return mediadomain.ErrLQConflict
		}
		return nil
	}
}
func (repo *repository) TranscodeGarbage(ctx context.Context, id string) ([]mediadomain.TranscodeItem, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT "+transcodeColumns+" FROM video_transcode_items WHERE task_id=? AND status='completed' AND collected=0 ORDER BY position LIMIT 64", id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []mediadomain.TranscodeItem{}
	for rows.Next() {
		item, err := scanTranscode(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}
func (repo *repository) TranscodeCollected(ctx context.Context, id, media string) error {
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE video_transcode_items SET collected=1 WHERE task_id=? AND media_id=? AND status='completed'", id, media)
	return err
}
func (repo *repository) TranscodeItems(ctx context.Context, id string, page, size int) (mediadomain.TranscodePage, error) {
	result := mediadomain.TranscodePage{Items: []mediadomain.TranscodeItem{}, Page: page, PageSize: size}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	var kind string
	err = tx.QueryRowContext(ctx, "SELECT type FROM tasks WHERE id=?", id).Scan(&kind)
	if errors.Is(err, sql.ErrNoRows) || err == nil && kind != "video_transcode" {
		return result, mediadomain.ErrLQNotFound
	}
	if err != nil {
		return result, err
	}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM video_transcode_items WHERE task_id=?", id).Scan(&result.Total); err != nil {
		return result, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT media_id,position,status,error_code FROM video_transcode_items WHERE task_id=? ORDER BY position LIMIT ? OFFSET ?", id, size, (page-1)*size)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var item mediadomain.TranscodeItem
		if err = rows.Scan(&item.MediaID, &item.Position, &item.Status, &item.ErrorCode); err != nil {
			rows.Close()
			return result, err
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
