package sqlite

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
)

type repository struct{ database *persistence.Database }

func New(database *persistence.Database) *repository { return &repository{database} }

var _ exportports.Repository = (*repository)(nil)

func (repo *repository) Cancelled(ctx context.Context, id string) (bool, error) {
	var cancelled bool
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM tasks t JOIN export_requests e ON e.task_id=t.id WHERE t.id=? AND t.type='archive_export' AND t.status='cancelled' AND t.manifest IS NULL)", id).Scan(&cancelled)
	return cancelled, err
}

func (repo *repository) Title(ctx context.Context, id string) (string, error) {
	var title string
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT title FROM active_comics AS comics WHERE id=?", id).Scan(&title)
	if errors.Is(err, sql.ErrNoRows) {
		err = exportdomain.ErrNotFound
	}
	return title, err
}
func (repo *repository) Admission(comic string, request exportdomain.Request) jobsports.Admission {
	return func(id string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			var conflict bool
			if err := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM upload_sessions WHERE comic_id=? AND status IN ('submitted','activated'))", comic).Scan(&conflict); err != nil {
				return err
			}
			if conflict {
				return exportdomain.ErrConflict
			}
			if err := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM metadata_refresh_runs WHERE comic_id=? AND applied=1 AND completed=0)", comic).Scan(&conflict); err != nil {
				return err
			}
			if conflict {
				return exportdomain.ErrConflict
			}
			if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM tasks WHERE comic_id=? AND id<>? AND type IN ('archive_export','image_lq','hq_release','video_transcode','media_upload','metadata_refresh') AND status IN ('queued','running','cancelling')) OR EXISTS(SELECT 1 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND m.hq_state='releasing')`, comic, id, comic).Scan(&conflict); err != nil {
				return err
			}
			if conflict {
				return exportdomain.ErrConflict
			}
			if err := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM video_transcode_items i JOIN tasks t ON t.id=i.task_id WHERE t.comic_id=? AND i.status='prepared')", comic).Scan(&conflict); err != nil {
				return err
			}
			if conflict {
				return exportdomain.ErrConflict
			}
			snapshot, err := loadSnapshot(ctx, tx, comic, request)
			if err != nil {
				return err
			}
			if _, err = exportdomain.Entries(snapshot); err != nil {
				return err
			}
			data, err := json.Marshal(snapshot)
			if err != nil {
				return err
			}
			if len(data) > exportdomain.MaxSnapshotBytes {
				return exportdomain.ErrValidation
			}
			digest := sha256.Sum256(data)
			if _, err = tx.ExecContext(ctx, "INSERT INTO export_requests(task_id,snapshot_json,snapshot_sha) VALUES(?,?,?)", id, string(data), hex.EncodeToString(digest[:])); err != nil {
				return err
			}
			_, err = tx.ExecContext(ctx, "UPDATE tasks SET title=? WHERE id=?", snapshot.Comic.Title, id)
			return err
		}
	}
}
func (repo *repository) Load(ctx context.Context, id string) (exportdomain.Snapshot, string, error) {
	var result exportdomain.Snapshot
	var data, hash string
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT CASE WHEN length(CAST(snapshot_json AS BLOB))<=? THEN snapshot_json ELSE '' END,snapshot_sha FROM export_requests WHERE task_id=?", exportdomain.MaxSnapshotBytes, id).Scan(&data, &hash)
	if errors.Is(err, sql.ErrNoRows) {
		return result, "", exportdomain.ErrNotFound
	}
	if err != nil {
		return result, "", err
	}
	digest := sha256.Sum256([]byte(data))
	if hex.EncodeToString(digest[:]) != hash || json.Unmarshal([]byte(data), &result) != nil || exportdomain.ValidateSnapshot(result) != nil {
		return result, "", exportdomain.ErrManifest
	}
	return result, hash, nil
}
func (repo *repository) Completion(manifest exportdomain.Manifest) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var hash string
		if err := tx.QueryRowContext(ctx, "SELECT snapshot_sha FROM export_requests WHERE task_id=?", manifest.TaskID).Scan(&hash); err != nil {
			return err
		}
		if hash != manifest.SnapshotSHA {
			return exportdomain.ErrManifest
		}
		if _, err := tx.ExecContext(ctx, "DELETE FROM export_artifacts WHERE task_id=?", manifest.TaskID); err != nil {
			return err
		}
		for index, artifact := range manifest.Artifacts {
			if _, err := tx.ExecContext(ctx, "INSERT INTO export_artifacts(task_id,position,file_name,size,sha256,last_segment) VALUES(?,?,?,?,?,?)", manifest.TaskID, index+1, artifact.FileName, artifact.Size, artifact.SHA256, artifact.LastSegment); err != nil {
				return err
			}
		}
		return nil
	}
}
func (repo *repository) Artifacts(ctx context.Context, id string) (exportdomain.ArtifactView, error) {
	view := exportdomain.ArtifactView{TaskID: id, Items: []exportdomain.Artifact{}}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return view, err
	}
	defer tx.Rollback()
	err = tx.QueryRowContext(ctx, "SELECT t.status FROM tasks t JOIN export_requests e ON e.task_id=t.id WHERE t.id=? AND t.type='archive_export'", id).Scan(&view.Status)
	if errors.Is(err, sql.ErrNoRows) {
		return view, exportdomain.ErrNotFound
	}
	if err != nil {
		return view, err
	}
	view.Published = view.Status == "succeeded"
	if view.Published {
		rows, err := tx.QueryContext(ctx, "SELECT file_name,size,sha256,last_segment FROM export_artifacts WHERE task_id=? ORDER BY position", id)
		if err != nil {
			return view, err
		}
		for rows.Next() {
			var item exportdomain.Artifact
			if err = rows.Scan(&item.FileName, &item.Size, &item.SHA256, &item.LastSegment); err != nil {
				rows.Close()
				return view, err
			}
			view.Items = append(view.Items, item)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return view, err
		}
	}
	return view, tx.Commit()
}
