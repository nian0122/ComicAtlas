package sqlite

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

var _ mediaports.UploadRepository = (*repository)(nil)

const uploadHeaderColumns = "id,comic_id,chapter_id,replace_media_id,status,total_bytes,created_at,expires_at,cleaned,previous_json,task_id"

func scanUpload(scanner interface{ Scan(...any) error }) (mediadomain.UploadSession, error) {
	value := mediadomain.UploadSession{ChunkSize: mediadomain.UploadChunkBytes, Files: []mediadomain.UploadFile{}}
	var previous *string
	var cleaned bool
	err := scanner.Scan(&value.ID, &value.ComicID, &value.ChapterID, &value.ReplaceMediaID, &value.Status, &value.TotalBytes, &value.CreatedAt, &value.ExpiresAt, &cleaned, &previous, &value.TaskID)
	if errors.Is(err, sql.ErrNoRows) {
		err = mediadomain.ErrUploadNotFound
	}
	value.CleanupPending = mediadomain.UploadCleanupPending(value.Status, cleaned)
	if err == nil && previous != nil {
		var source mediadomain.UploadPrevious
		err = json.Unmarshal([]byte(*previous), &source)
		value.Previous = &source
	}
	return value, err
}
func (repo *repository) CreateUpload(ctx context.Context, value mediadomain.UploadSession, key, hash string, space mediadomain.UploadSpace, limits mediadomain.UploadLimits) (string, error) {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	if key != "" {
		var id, stored string
		err = tx.QueryRowContext(ctx, "SELECT id,request_hash FROM upload_sessions WHERE idempotency_key=?", key).Scan(&id, &stored)
		if err == nil {
			if stored != hash {
				return "", mediadomain.ErrUploadConflict
			}
			return id, nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return "", err
		}
	}
	var active int
	var reserved int64
	if err = tx.QueryRowContext(ctx, `SELECT COUNT(*),COALESCE(SUM(s.total_bytes-COALESCE((SELECT SUM(f.received_bytes) FROM upload_files f WHERE f.session_id=s.id),0)),0) FROM upload_sessions s WHERE s.status='active'`).Scan(&active, &reserved); err != nil {
		return "", err
	}
	if err = mediadomain.UploadBudget(space, limits, reserved, value.TotalBytes, active); err != nil {
		return "", err
	}
	err = tx.QueryRowContext(ctx, "SELECT comic_id FROM active_chapters AS chapters WHERE id=?", value.ChapterID).Scan(&value.ComicID)
	if errors.Is(err, sql.ErrNoRows) {
		return "", mediadomain.ErrUploadNotFound
	}
	if err != nil {
		return "", err
	}
	var previous any
	var lifecycle int
	if err = tx.QueryRowContext(ctx, "SELECT (SELECT COUNT(*) FROM lifecycle_runs WHERE comic_id=? AND completed=0)+(SELECT COUNT(*) FROM tasks WHERE comic_id=? AND type='lifecycle_audit' AND status IN('queued','running','cancelling'))", value.ComicID, value.ComicID).Scan(&lifecycle); err != nil {
		return "", err
	}
	if lifecycle > 0 {
		return "", mediadomain.ErrUploadConflict
	}
	if value.ReplaceMediaID != nil {
		var source mediadomain.UploadPrevious
		err = tx.QueryRowContext(ctx, "SELECT relative_path,sha256,size,hq_state FROM active_media AS media WHERE id=? AND chapter_id=?", *value.ReplaceMediaID, value.ChapterID).Scan(&source.Path, &source.SHA256, &source.Size, &source.State)
		if errors.Is(err, sql.ErrNoRows) {
			return "", mediadomain.ErrUploadNotFound
		}
		if err != nil {
			return "", err
		}
		if !mediadomain.UploadReplacementAllowed(source.State) {
			return "", mediadomain.ErrUploadConflict
		}
		data, err := json.Marshal(source)
		if err != nil {
			return "", err
		}
		previous = string(data)
	}
	var current, appendReserved int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM (SELECT id FROM active_media AS media WHERE chapter_id=? LIMIT ?)", value.ChapterID, mediadomain.MaxUploadFiles+1).Scan(&current); err != nil {
		return "", err
	}
	if err = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM upload_files f JOIN upload_sessions s ON s.id=f.session_id WHERE s.chapter_id=? AND s.status IN ('active','submitted') AND s.replace_media_id IS NULL`, value.ChapterID).Scan(&appendReserved); err != nil {
		return "", err
	}
	if err = mediadomain.UploadCapacity(current, appendReserved, len(value.Files), value.ReplaceMediaID != nil); err != nil {
		return "", err
	}
	var excluded any
	if value.ReplaceMediaID != nil {
		excluded = *value.ReplaceMediaID
	}
	rows, err := tx.QueryContext(ctx, "SELECT file_name FROM active_media AS media WHERE chapter_id=? AND (? IS NULL OR id<>?) LIMIT ?", value.ChapterID, excluded, excluded, mediadomain.MaxUploadFiles+1)
	if err != nil {
		return "", err
	}
	existing := []string{}
	for rows.Next() {
		var name string
		if err = rows.Scan(&name); err != nil {
			rows.Close()
			return "", err
		}
		existing = append(existing, name)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return "", err
	}
	rows, err = tx.QueryContext(ctx, `SELECT f.name FROM upload_files f JOIN upload_sessions s ON s.id=f.session_id WHERE s.chapter_id=? AND s.status IN ('active','submitted','activated') LIMIT ?`, value.ChapterID, mediadomain.MaxUploadFiles+mediadomain.MaxUploadSessions+1)
	if err != nil {
		return "", err
	}
	names := []string{}
	for rows.Next() {
		var name string
		if err = rows.Scan(&name); err != nil {
			rows.Close()
			return "", err
		}
		names = append(names, name)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return "", err
	}
	if err = mediadomain.UploadNames(existing, names, value.Files); err != nil {
		return "", err
	}
	var keyValue any
	if key != "" {
		keyValue = key
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO upload_sessions(id,comic_id,chapter_id,replace_media_id,status,total_bytes,created_at,expires_at,request_hash,idempotency_key,previous_json) VALUES(?,?,?,?,'active',?,?,?,?,?,?)`, value.ID, value.ComicID, value.ChapterID, value.ReplaceMediaID, value.TotalBytes, value.CreatedAt, value.ExpiresAt, hash, keyValue, previous); err != nil {
		return "", err
	}
	statement, err := tx.PrepareContext(ctx, `INSERT INTO upload_files(session_id,id,position,name,name_key,size,sha256) VALUES(?,?,?,?,?,?,?)`)
	if err != nil {
		return "", err
	}
	defer statement.Close()
	for index, file := range value.Files {
		if _, err = statement.ExecContext(ctx, value.ID, file.ID, index+1, file.Name, strings.ToLower(file.Name), file.Size, file.SHA256); err != nil {
			return "", err
		}
	}
	return value.ID, tx.Commit()
}
func (repo *repository) UploadSession(ctx context.Context, id string) (mediadomain.UploadSession, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return mediadomain.UploadSession{}, err
	}
	defer tx.Rollback()
	value, err := scanUpload(tx.QueryRowContext(ctx, "SELECT "+uploadHeaderColumns+" FROM upload_sessions WHERE id=?", id))
	if err != nil {
		return value, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT id,name,size,sha256,received_bytes,target_path FROM upload_files WHERE session_id=? ORDER BY position", id)
	if err != nil {
		return value, err
	}
	positions := map[string]int{}
	for rows.Next() {
		var file mediadomain.UploadFile
		var target *string
		file.ReceivedParts = []int{}
		if err = rows.Scan(&file.ID, &file.Name, &file.Size, &file.SHA256, &file.ReceivedBytes, &target); err != nil {
			rows.Close()
			return value, err
		}
		if target != nil && *target != "" {
			value.CleanupFiles = append(value.CleanupFiles, mediadomain.UploadRetired{Kind: "hq", Path: *target, SHA256: file.SHA256, Size: file.Size})
		}
		positions[file.ID] = len(value.Files)
		value.Files = append(value.Files, file)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return value, err
	}
	rows, err = tx.QueryContext(ctx, "SELECT file_id,part FROM upload_parts WHERE session_id=? ORDER BY file_id,part", id)
	if err != nil {
		return value, err
	}
	for rows.Next() {
		var file string
		var part int
		if err = rows.Scan(&file, &part); err != nil {
			rows.Close()
			return value, err
		}
		index, ok := positions[file]
		if !ok {
			rows.Close()
			return value, mediadomain.ErrUploadIO
		}
		value.Files[index].ReceivedParts = append(value.Files[index].ReceivedParts, part)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}
func (repo *repository) UploadFile(ctx context.Context, id, fileID string) (mediadomain.UploadSession, mediadomain.UploadFile, error) {
	value, err := scanUpload(repo.database.Reader.QueryRowContext(ctx, "SELECT "+uploadHeaderColumns+" FROM upload_sessions WHERE id=?", id))
	var file mediadomain.UploadFile
	if err != nil {
		return value, file, err
	}
	err = repo.database.Reader.QueryRowContext(ctx, "SELECT id,name,size,sha256,received_bytes FROM upload_files WHERE session_id=? AND id=?", id, fileID).Scan(&file.ID, &file.Name, &file.Size, &file.SHA256, &file.ReceivedBytes)
	if errors.Is(err, sql.ErrNoRows) {
		err = mediadomain.ErrUploadNotFound
	}
	return value, file, err
}
func (repo *repository) UploadPart(ctx context.Context, id, file string, index int) (*mediadomain.UploadPart, error) {
	value := mediadomain.UploadPart{FileID: file, Part: index}
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT size,sha256 FROM upload_parts WHERE session_id=? AND file_id=? AND part=?", id, file, index).Scan(&value.Size, &value.SHA256)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &value, err
}
func (repo *repository) RecordUploadPart(ctx context.Context, id string, part mediadomain.UploadPart, now string) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	value, err := scanUpload(tx.QueryRowContext(ctx, "SELECT "+uploadHeaderColumns+" FROM upload_sessions WHERE id=?", id))
	if err != nil {
		return err
	}
	if err = mediadomain.UploadActive(value, now); err != nil {
		return err
	}
	var size int64
	err = tx.QueryRowContext(ctx, "SELECT size FROM upload_files WHERE session_id=? AND id=?", id, part.FileID).Scan(&size)
	if errors.Is(err, sql.ErrNoRows) {
		return mediadomain.ErrUploadNotFound
	}
	if err != nil {
		return err
	}
	if err = mediadomain.ValidateUploadPart(part, size); err != nil {
		return err
	}
	var stored string
	err = tx.QueryRowContext(ctx, "SELECT sha256 FROM upload_parts WHERE session_id=? AND file_id=? AND part=?", id, part.FileID, part.Part).Scan(&stored)
	if err == nil {
		if stored != part.SHA256 {
			return mediadomain.ErrUploadChecksum
		}
		return nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO upload_parts VALUES(?,?,?,?,?)", id, part.FileID, part.Part, part.Size, part.SHA256); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE upload_files SET received_bytes=received_bytes+? WHERE session_id=? AND id=?", part.Size, id, part.FileID); err != nil {
		return err
	}
	return tx.Commit()
}
func (repo *repository) EndUpload(ctx context.Context, id, status, now string) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	value, err := scanUpload(tx.QueryRowContext(ctx, "SELECT "+uploadHeaderColumns+" FROM upload_sessions WHERE id=?", id))
	if err != nil {
		return err
	}
	if value.Status == "submitted" && status == "cancelled" && value.TaskID != nil {
		var taskStatus string
		if err = tx.QueryRowContext(ctx, "SELECT status FROM tasks WHERE id=?", *value.TaskID).Scan(&taskStatus); err != nil {
			return err
		}
		if !mediadomain.UploadMayAbandon(value.Status, taskStatus) {
			return mediadomain.ErrUploadConflict
		}
		if _, err = tx.ExecContext(ctx, "UPDATE tasks SET status='cancelled',stage='abandoned',error_code=NULL,finished_at=? WHERE id=?", now, *value.TaskID); err != nil {
			return err
		}
	} else if err = mediadomain.UploadEnd(value, status, now); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "UPDATE upload_sessions SET status=? WHERE id=? AND status IN ('active','submitted')", status, id)
	if err != nil {
		return err
	}
	return tx.Commit()
}
func (repo *repository) ExpireUploads(ctx context.Context, now string) error {
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE upload_sessions SET status='expired' WHERE status='active' AND expires_at<=?", now)
	return err
}
func (repo *repository) UploadCleanup(ctx context.Context, afterID string) ([]string, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT id FROM upload_sessions WHERE id>? AND status IN ('cancelled','expired') AND cleaned=0 ORDER BY id LIMIT 64", afterID)
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
func (repo *repository) UploadCleaned(ctx context.Context, id string) error {
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE upload_sessions SET cleaned=1 WHERE id=? AND status IN ('cancelled','expired')", id)
	return err
}
