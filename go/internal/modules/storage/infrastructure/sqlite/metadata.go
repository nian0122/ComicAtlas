package sqlite

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	storageports "comicatlas.local/backend/internal/modules/storage/application/ports"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

var _ storageports.MetadataRepository = (*repository)(nil)

func (repo *repository) MetadataAdmission(snapshot librarydomain.Snapshot, guard persistence.WriteAction) jobsports.Admission {
	data, _, marshalErr := librarydomain.SnapshotData(snapshot)
	return func(id string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			if marshalErr != nil {
				return marshalErr
			}
			var busy bool
			err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM tasks WHERE comic_id=? AND id<>? AND type IN('archive_export','image_lq','hq_release','video_transcode','media_upload','metadata_refresh') AND status IN('queued','running','cancelling'))
		 OR EXISTS(SELECT 1 FROM upload_sessions WHERE comic_id=? AND status IN('submitted','activated'))
		 OR EXISTS(SELECT 1 FROM video_transcode_items i JOIN tasks t ON t.id=i.task_id WHERE t.comic_id=? AND i.status='prepared')
		 OR EXISTS(SELECT 1 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND m.hq_state='releasing')
		 OR EXISTS(SELECT 1 FROM metadata_refresh_runs WHERE comic_id=? AND applied=1 AND completed=0)
		 OR EXISTS(SELECT 1 FROM comic_recovery_placeholders WHERE comic_id=?)`, snapshot.Detail.ID, id, snapshot.Detail.ID, snapshot.Detail.ID, snapshot.Detail.ID, snapshot.Detail.ID, snapshot.Detail.ID).Scan(&busy)
			if err != nil {
				return err
			}
			if busy {
				return storagedomain.ErrConflict
			}
			if err = guard(ctx, tx); err != nil {
				return err
			}
			_, err = tx.ExecContext(ctx, "INSERT INTO metadata_refresh_runs(task_id,comic_id,baseline_json) VALUES(?,?,?)", id, snapshot.Detail.ID, string(data))
			return err
		}
	}
}
func (repo *repository) MetadataRun(ctx context.Context, id string) (storagedomain.MetadataRun, error) {
	var run storagedomain.MetadataRun
	var data string
	var next *string
	var observed *string
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT baseline_json,next_json,applied,observed_at FROM metadata_refresh_runs WHERE task_id=? AND NOT EXISTS(SELECT 1 FROM comic_recovery_placeholders p WHERE p.comic_id=metadata_refresh_runs.comic_id)", id).Scan(&data, &next, &run.Applied, &observed)
	if observed != nil {
		run.ObservedAt = *observed
	}
	if errors.Is(err, sql.ErrNoRows) {
		err = storagedomain.ErrNotFound
	}
	if err != nil {
		return run, err
	}
	if len(data) > librarydomain.MaxSnapshotBytes || json.Unmarshal([]byte(data), &run.Baseline) != nil {
		return run, storagedomain.ErrValidation
	}
	if next != nil {
		run.Next = &librarydomain.Snapshot{}
		if len(*next) > librarydomain.MaxSnapshotBytes || json.Unmarshal([]byte(*next), run.Next) != nil {
			return run, storagedomain.ErrValidation
		}
	}
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT item_json FROM metadata_refresh_items WHERE task_id=? ORDER BY position LIMIT ?", id, librarydomain.MaxSnapshotMedia*2+1)
	if err != nil {
		return run, err
	}
	defer rows.Close()
	run.Observations = []storagedomain.MediaObservation{}
	for rows.Next() {
		var raw string
		var item storagedomain.MediaObservation
		if err = rows.Scan(&raw); err != nil {
			return run, err
		}
		if json.Unmarshal([]byte(raw), &item) != nil {
			return run, storagedomain.ErrValidation
		}
		run.Observations = append(run.Observations, item)
	}
	if len(run.Observations) > librarydomain.MaxSnapshotMedia*2 {
		return run, storagedomain.ErrScanLimit
	}
	if err = rows.Err(); err != nil {
		return run, err
	}
	if err = storagedomain.ValidateMetadataRun(run); err != nil {
		return run, err
	}
	return run, nil
}
func (repo *repository) PrepareMetadata(ctx context.Context, id string, next librarydomain.Snapshot, items []storagedomain.MediaObservation, observedAt string) error {
	data, _, err := librarydomain.SnapshotData(next)
	if err != nil {
		return err
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var allowed bool
	if err = tx.QueryRowContext(ctx, "SELECT r.applied=0 AND t.status='running' FROM metadata_refresh_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=?", id).Scan(&allowed); err != nil {
		return err
	}
	if !allowed {
		return storagedomain.ErrConflict
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM metadata_refresh_items WHERE task_id=?", id); err != nil {
		return err
	}
	statement, err := tx.PrepareContext(ctx, "INSERT INTO metadata_refresh_items(task_id,position,media_id,chapter_id,file_name,status,error_code,item_json) VALUES(?,?,?,?,?,?,?,?)")
	if err != nil {
		return err
	}
	defer statement.Close()
	for _, item := range items {
		raw, err := json.Marshal(item)
		if err != nil {
			return err
		}
		if _, err = statement.ExecContext(ctx, id, item.Position, item.MediaID, item.ChapterID, item.FileName, item.Status, item.ErrorCode, string(raw)); err != nil {
			return err
		}
	}
	if _, err = tx.ExecContext(ctx, "UPDATE metadata_refresh_runs SET next_json=?,observed_at=? WHERE task_id=?", string(data), observedAt, id); err != nil {
		return err
	}
	return tx.Commit()
}
func (repo *repository) ActivateMetadata(ctx context.Context, id string, persist persistence.WriteAction) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var applied bool
	var status, stage string
	if err = tx.QueryRowContext(ctx, "SELECT r.applied,t.status,t.stage FROM metadata_refresh_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=?", id).Scan(&applied, &status, &stage); err != nil {
		return err
	}
	var placeholder bool
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM comic_recovery_placeholders p JOIN metadata_refresh_runs r ON r.comic_id=p.comic_id WHERE r.task_id=?)", id).Scan(&placeholder); err != nil {
		return err
	}
	if placeholder {
		return storagedomain.ErrConflict
	}
	if applied {
		return nil
	}
	if status != "running" || stage != "publishing" {
		return storagedomain.ErrConflict
	}
	if err = persist(ctx, tx); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO media_observations(media_id,task_id,status,source_sha256) SELECT i.media_id,i.task_id,CASE WHEN i.status='discovered' THEN 'present' ELSE i.status END,m.sha256 FROM metadata_refresh_items i JOIN active_media m ON m.id=i.media_id WHERE i.task_id=?
	 ON CONFLICT(media_id) DO UPDATE SET task_id=excluded.task_id,status=excluded.status,source_sha256=excluded.source_sha256`, id); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE metadata_refresh_runs SET applied=1 WHERE task_id=?", id); err != nil {
		return err
	}
	return tx.Commit()
}
func (repo *repository) MetadataCompletion(id, hash string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		result, err := tx.ExecContext(ctx, "UPDATE metadata_refresh_runs SET completed=1,sidecar_sha=? WHERE task_id=? AND applied=1", hash, id)
		if err != nil {
			return err
		}
		count, err := result.RowsAffected()
		if err != nil {
			return err
		}
		if count != 1 {
			return storagedomain.ErrConflict
		}
		return nil
	}
}
func (repo *repository) MetadataItems(ctx context.Context, id string, page, size int) (storagedomain.MetadataPage, error) {
	result := storagedomain.MetadataPage{Items: []storagedomain.MetadataItem{}, Page: page, PageSize: size}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	var exists bool
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM metadata_refresh_runs WHERE task_id=?)", id).Scan(&exists); err != nil {
		return result, err
	}
	if !exists {
		return result, storagedomain.ErrNotFound
	}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM metadata_refresh_items WHERE task_id=?", id).Scan(&result.Total); err != nil {
		return result, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT position,media_id,chapter_id,file_name,status,error_code FROM metadata_refresh_items WHERE task_id=? ORDER BY position LIMIT ? OFFSET ?", id, size, (page-1)*size)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var item storagedomain.MetadataItem
		if err = rows.Scan(&item.Position, &item.MediaID, &item.ChapterID, &item.FileName, &item.Status, &item.ErrorCode); err != nil {
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
