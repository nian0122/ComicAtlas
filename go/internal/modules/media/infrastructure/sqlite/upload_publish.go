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

var _ mediaports.UploadPublishRepository = (*repository)(nil)

func blockUnfinishedUpload(ctx context.Context, tx *sql.Tx, comic, exclude string) error {
	var found bool
	err := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM upload_sessions WHERE comic_id=? AND status IN ('submitted','activated') AND task_id<>?)", comic, exclude).Scan(&found)
	if err != nil {
		return err
	}
	if found {
		return mediadomain.ErrLQConflict
	}
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM metadata_refresh_runs WHERE comic_id=? AND task_id<>? AND applied=1 AND completed=0)", comic, exclude).Scan(&found); err != nil {
		return err
	}
	if found {
		return mediadomain.ErrLQConflict
	}
	return nil
}
func uploadTarget(ctx context.Context, tx *sql.Tx, session mediadomain.UploadSession) error {
	var chapter string
	err := tx.QueryRowContext(ctx, "SELECT comic_id FROM active_chapters AS chapters WHERE id=?", session.ChapterID).Scan(&chapter)
	if errors.Is(err, sql.ErrNoRows) || err == nil && chapter != session.ComicID {
		return mediadomain.ErrUploadNotFound
	}
	if err != nil {
		return err
	}
	if session.ReplaceMediaID != nil {
		var current mediadomain.UploadPrevious
		err = tx.QueryRowContext(ctx, "SELECT relative_path,sha256,hq_state,size FROM active_media AS media WHERE id=? AND chapter_id=?", *session.ReplaceMediaID, session.ChapterID).Scan(&current.Path, &current.SHA256, &current.State, &current.Size)
		if errors.Is(err, sql.ErrNoRows) {
			return mediadomain.ErrUploadNotFound
		}
		if err != nil {
			return err
		}
		if !mediadomain.UploadPreviousMatches(session.Previous, current) {
			return mediadomain.ErrUploadConflict
		}
	}
	return nil
}
func (repo *repository) UploadAdmission(expected mediadomain.UploadSession, now string) jobsports.Admission {
	return func(id string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			session, err := scanUpload(tx.QueryRowContext(ctx, "SELECT "+uploadHeaderColumns+" FROM upload_sessions WHERE id=?", expected.ID))
			if err != nil {
				return err
			}
			if err = mediadomain.UploadActive(session, now); err != nil {
				return err
			}
			if session.Status != "active" || session.TaskID != nil {
				return mediadomain.ErrUploadConflict
			}
			if err = uploadTarget(ctx, tx, session); err != nil {
				return err
			}
			if err = blockPreparedVideo(ctx, tx, session.ComicID, id); err != nil {
				return err
			}
			if err = blockUnfinishedUpload(ctx, tx, session.ComicID, id); err != nil {
				return err
			}
			var active, incomplete int
			if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE comic_id=? AND id<>? AND type IN ('archive_export','image_lq','hq_release','video_transcode','media_upload','metadata_refresh') AND status IN ('queued','running','cancelling')", session.ComicID, id).Scan(&active); err != nil {
				return err
			}
			if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND m.hq_state='releasing'", session.ComicID).Scan(&incomplete); err != nil {
				return err
			}
			if err = mediadomain.ValidateTranscodeSelection(0, active, incomplete); err != nil {
				return err
			}
			var missing, total int
			if err = tx.QueryRowContext(ctx, `SELECT COUNT(*),COALESCE(SUM(received_bytes<>size OR (SELECT COUNT(*) FROM upload_parts p WHERE p.session_id=f.session_id AND p.file_id=f.id)<>(size+?)/?),0) FROM upload_files f WHERE f.session_id=?`, mediadomain.UploadChunkBytes-1, mediadomain.UploadChunkBytes, session.ID).Scan(&total, &missing); err != nil {
				return err
			}
			if missing != 0 || total != len(expected.Files) {
				return mediadomain.ErrUploadConflict
			}
			statement, err := tx.PrepareContext(ctx, "UPDATE upload_files SET media_id=? WHERE session_id=? AND id=?")
			if err != nil {
				return err
			}
			defer statement.Close()
			for _, file := range expected.Files {
				if _, err = statement.ExecContext(ctx, mediadomain.UploadPublishedID(file, session.ReplaceMediaID), session.ID, file.ID); err != nil {
					return err
				}
			}
			_, err = tx.ExecContext(ctx, "UPDATE upload_sessions SET status='submitted',task_id=? WHERE id=?", id, session.ID)
			return err
		}
	}
}
func (repo *repository) UploadJob(ctx context.Context, id string) (mediadomain.UploadSession, error) {
	return scanUpload(repo.database.Reader.QueryRowContext(ctx, "SELECT "+uploadHeaderColumns+" FROM upload_sessions WHERE task_id=?", id))
}
func scanUploadPublishing(scanner interface{ Scan(...any) error }) (mediadomain.UploadPublishFile, error) {
	var file mediadomain.UploadPublishFile
	var info *string
	err := scanner.Scan(&file.FileID, &file.MediaID, &file.Position, &file.Name, &file.Size, &file.SHA256, &file.Status, &file.ErrorCode, &info)
	if err == nil && info != nil {
		var value mediadomain.Inspection
		err = json.Unmarshal([]byte(*info), &value)
		file.Info = &value
	}
	return file, err
}

const uploadPublishColumns = "id,media_id,position,name,size,sha256,publish_state,error_code,inspection_json"

func (repo *repository) UploadBatch(ctx context.Context, id string, after int) ([]mediadomain.UploadPublishFile, error) {
	session, sessionErr := repo.UploadJob(ctx, id)
	if sessionErr != nil {
		return nil, sessionErr
	}
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT "+uploadPublishColumns+" FROM upload_files WHERE session_id=(SELECT id FROM upload_sessions WHERE task_id=?) AND position>? ORDER BY position LIMIT 64", id, after)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	files := []mediadomain.UploadPublishFile{}
	for rows.Next() {
		file, err := scanUploadPublishing(rows)
		if err != nil {
			return nil, err
		}
		file.ComicID, file.ChapterID = session.ComicID, session.ChapterID
		files = append(files, file)
	}
	return files, rows.Err()
}
func (repo *repository) UploadPrepared(ctx context.Context, id string, file mediadomain.UploadPublishFile, info mediadomain.Inspection) error {
	if err := mediadomain.ValidateInspection(info, file.Name); err != nil {
		return err
	}
	data, err := json.Marshal(info)
	if err != nil {
		return err
	}
	session, err := repo.UploadJob(ctx, id)
	if err != nil {
		return err
	}
	file.Info = &info
	file.ComicID, file.ChapterID = session.ComicID, session.ChapterID
	result, err := repo.database.Writer.ExecContext(ctx, "UPDATE upload_files SET inspection_json=?,target_path=?,publish_state='prepared',error_code=NULL WHERE session_id=(SELECT id FROM upload_sessions WHERE task_id=? AND status='submitted') AND id=? AND publish_state='pending'", string(data), mediadomain.UploadTargetPath(file), id, file.FileID)
	return transcodeUpdated(result, err)
}
func (repo *repository) UploadPublished(ctx context.Context, id, file string) error {
	result, err := repo.database.Writer.ExecContext(ctx, "UPDATE upload_files SET publish_state='published',error_code=NULL WHERE session_id=(SELECT id FROM upload_sessions WHERE task_id=? AND status='submitted') AND id=? AND publish_state IN ('prepared','published')", id, file)
	return transcodeUpdated(result, err)
}
func (repo *repository) UploadFileFailure(ctx context.Context, id, file, code string) error {
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE upload_files SET error_code=? WHERE session_id=(SELECT id FROM upload_sessions WHERE task_id=?) AND id=?", code, id, file)
	return err
}
func (repo *repository) UploadProgress(ctx context.Context, id string) (int, int, error) {
	var total, done int
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT COUNT(*),COALESCE(SUM(publish_state='published'),0) FROM upload_files WHERE session_id=(SELECT id FROM upload_sessions WHERE task_id=?)", id).Scan(&total, &done)
	return total, done, err
}
func uploadActivateNames(ctx context.Context, tx *sql.Tx, session mediadomain.UploadSession) error {
	var conflict bool
	err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM upload_files f JOIN active_media m ON m.chapter_id=? AND COMIC_LOWER(m.file_name)=f.name_key WHERE f.session_id=? AND (? IS NULL OR m.id<>?))`, session.ChapterID, session.ID, session.ReplaceMediaID, session.ReplaceMediaID).Scan(&conflict)
	if err != nil {
		return err
	}
	if conflict {
		return mediadomain.ErrUploadConflict
	}
	return nil
}
func (repo *repository) ActivateUpload(ctx context.Context, id string) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	session, err := scanUpload(tx.QueryRowContext(ctx, "SELECT "+uploadHeaderColumns+" FROM upload_sessions WHERE task_id=?", id))
	if err != nil {
		return err
	}
	if session.Status == "activated" {
		return nil
	}
	if session.Status != "submitted" {
		return mediadomain.ErrUploadConflict
	}
	var running bool
	if err = tx.QueryRowContext(ctx, "SELECT status='running' FROM tasks WHERE id=?", id).Scan(&running); err != nil {
		return err
	}
	if !running {
		return mediadomain.ErrUploadConflict
	}
	if err = uploadTarget(ctx, tx, session); err != nil {
		return err
	}
	if err = uploadActivateNames(ctx, tx, session); err != nil {
		return err
	}
	var pending, count int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*),COALESCE(SUM(publish_state<>'published' OR inspection_json IS NULL OR target_path IS NULL),0) FROM upload_files WHERE session_id=?", session.ID).Scan(&count, &pending); err != nil {
		return err
	}
	if count == 0 || pending != 0 {
		return mediadomain.ErrUploadConflict
	}
	if session.ReplaceMediaID == nil {
		var current, maxPage int
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*),COALESCE(MAX(page_number),0) FROM active_media AS media WHERE chapter_id=?", session.ChapterID).Scan(&current, &maxPage); err != nil {
			return err
		}
		base, err := mediadomain.UploadAppendBase(current, count, maxPage)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO media(id,chapter_id,page_number,file_name,relative_path,media_type,content_type,size,width,height,sha256,video_metadata,hq_state)
		 SELECT media_id,?,?+position,name,target_path,json_extract(inspection_json,'$.mediaType'),json_extract(inspection_json,'$.contentType'),size,json_extract(inspection_json,'$.width'),json_extract(inspection_json,'$.height'),sha256,json_extract(inspection_json,'$.video'),'available' FROM upload_files WHERE session_id=? ORDER BY position`, session.ChapterID, base, session.ID)
		if err != nil {
			return err
		}
	} else {
		if count != 1 {
			return mediadomain.ErrUploadConflict
		}
		prior, err := uploadPrior(ctx, tx, *session.ReplaceMediaID)
		if err != nil {
			return err
		}
		snapshot, err := json.Marshal(prior)
		if err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, "UPDATE upload_sessions SET prior_json=? WHERE id=?", string(snapshot), session.ID); err != nil {
			return err
		}
		for _, item := range mediadomain.UploadRetirePlan(prior) {
			if _, err = tx.ExecContext(ctx, "INSERT INTO upload_retired(task_id,kind,path,sha256,size) VALUES(?,?,?,?,?)", id, item.Kind, item.Path, item.SHA256, item.Size); err != nil {
				return err
			}
		}
		if _, err = tx.ExecContext(ctx, "DELETE FROM image_lq_latest WHERE media_id=?", *session.ReplaceMediaID); err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, "DELETE FROM media_observations WHERE media_id=?", *session.ReplaceMediaID); err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, "DELETE FROM image_lq WHERE media_id=?", *session.ReplaceMediaID); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `UPDATE media SET (file_name,relative_path,media_type,content_type,size,width,height,sha256,video_metadata)=(SELECT name,target_path,json_extract(inspection_json,'$.mediaType'),json_extract(inspection_json,'$.contentType'),size,json_extract(inspection_json,'$.width'),json_extract(inspection_json,'$.height'),sha256,json_extract(inspection_json,'$.video') FROM upload_files WHERE session_id=?),hq_state='available' WHERE id=?`, session.ID, *session.ReplaceMediaID)
		if err != nil {
			return err
		}
	}
	if _, err = tx.ExecContext(ctx, "UPDATE chapters SET media_version=media_version+1 WHERE id=?", session.ChapterID); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE upload_sessions SET status='activated' WHERE id=?", session.ID); err != nil {
		return err
	}
	return tx.Commit()
}
func uploadPrior(ctx context.Context, tx *sql.Tx, id string) (mediadomain.UploadPrior, error) {
	var value mediadomain.UploadPrior
	var raw *string
	err := tx.QueryRowContext(ctx, "SELECT file_name,media_type,content_type,relative_path,sha256,hq_state,size,width,height,video_metadata FROM active_media AS media WHERE id=?", id).Scan(&value.Name, &value.MediaType, &value.ContentType, &value.Previous.Path, &value.Previous.SHA256, &value.Previous.State, &value.Previous.Size, &value.Width, &value.Height, &raw)
	if err != nil {
		return value, err
	}
	if raw != nil {
		var video mediadomain.Video
		if err = json.Unmarshal([]byte(*raw), &video); err != nil {
			return value, err
		}
		value.Video = &video
	}
	var record mediadomain.LQRecord
	err = tx.QueryRowContext(ctx, "SELECT relative_path,sha256,size FROM image_lq WHERE media_id=?", id).Scan(&record.Path, &record.SHA256, &record.Size)
	if err == nil {
		value.LQ = &record
	}
	if errors.Is(err, sql.ErrNoRows) {
		err = nil
	}
	return value, err
}
func (repo *repository) UploadRetirement(ctx context.Context, id string) ([]mediadomain.UploadRetired, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT kind,path,sha256,size FROM upload_retired WHERE task_id=? AND status='pending' ORDER BY kind", id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []mediadomain.UploadRetired{}
	for rows.Next() {
		var item mediadomain.UploadRetired
		if err = rows.Scan(&item.Kind, &item.Path, &item.SHA256, &item.Size); err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}
func (repo *repository) UploadRetired(ctx context.Context, id, kind, outcome string) error {
	if outcome != "moved" && outcome != "absent" {
		return mediadomain.ErrUploadConflict
	}
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE upload_retired SET status=? WHERE task_id=? AND kind=? AND status='pending'", outcome, id, kind)
	return err
}
func (repo *repository) UploadCompletion(id string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var pending int
		if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM upload_retired WHERE task_id=? AND status='pending'", id).Scan(&pending); err != nil {
			return err
		}
		if pending != 0 {
			return mediadomain.ErrUploadConflict
		}
		result, err := tx.ExecContext(ctx, "UPDATE upload_sessions SET status='completed',cleaned=1 WHERE task_id=? AND status='activated'", id)
		return transcodeUpdated(result, err)
	}
}
func (repo *repository) UploadItems(ctx context.Context, id string, page, size int) (mediadomain.UploadPublishPage, error) {
	value := mediadomain.UploadPublishPage{Items: []mediadomain.UploadPublishFile{}, Page: page, PageSize: size}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return value, err
	}
	defer tx.Rollback()
	var session string
	err = tx.QueryRowContext(ctx, "SELECT id FROM upload_sessions WHERE task_id=?", id).Scan(&session)
	if errors.Is(err, sql.ErrNoRows) {
		return value, mediadomain.ErrUploadNotFound
	}
	if err != nil {
		return value, err
	}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM upload_files WHERE session_id=?", session).Scan(&value.Total); err != nil {
		return value, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT id,media_id,position,publish_state,error_code FROM upload_files WHERE session_id=? ORDER BY position LIMIT ? OFFSET ?", session, size, (page-1)*size)
	if err != nil {
		return value, err
	}
	for rows.Next() {
		var file mediadomain.UploadPublishFile
		if err = rows.Scan(&file.FileID, &file.MediaID, &file.Position, &file.Status, &file.ErrorCode); err != nil {
			rows.Close()
			return value, err
		}
		value.Items = append(value.Items, file)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}
