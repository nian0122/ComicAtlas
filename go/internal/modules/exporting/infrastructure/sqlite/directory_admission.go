package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

func (repo *repository) DirectoryAdmissionScope(ctx context.Context, id string) (exportdomain.DirectoryAdmissionScope, error) {
	var result exportdomain.DirectoryAdmissionScope
	var data, stamp string
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT CASE WHEN length(CAST(scope_data AS BLOB))<=? THEN scope_data ELSE '' END,scope_sha FROM directory_move_previews WHERE id=?", exportdomain.MaxDirectoryScopeBytes, id).Scan(&data, &stamp)
	if errors.Is(err, sql.ErrNoRows) {
		return result, exportdomain.ErrNotFound
	}
	if err != nil {
		return result, err
	}
	return exportdomain.DecodeDirectoryScope(data, stamp)
}

func directoryAdmissionVersions(ctx context.Context, tx *sql.Tx, scope exportdomain.DirectoryAdmissionScope) (string, error) {
	ids := make([]string, 0, len(scope.Inventories))
	for _, identity := range scope.Inventories {
		ids = append(ids, identity.ComicID)
	}
	data, err := json.Marshal(ids)
	if err != nil {
		return "", err
	}
	rows, err := tx.QueryContext(ctx, `SELECT selected.value,COALESCE(v.revision,0) FROM json_each(?) selected
 LEFT JOIN active_comics c ON c.id=selected.value LEFT JOIN comic_change_versions v ON v.comic_id=c.id ORDER BY CAST(selected.key AS INTEGER)`, string(data))
	if err != nil {
		return "", err
	}
	current := make([]exportdomain.DirectoryCurrentVersion, 0, len(ids))
	for rows.Next() {
		var item exportdomain.DirectoryCurrentVersion
		if err = rows.Scan(&item.ComicID, &item.MutationVersion); err != nil {
			rows.Close()
			return "", err
		}
		current = append(current, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return "", err
	}
	if err = exportdomain.ValidateDirectoryVersions(scope.Inventories, current); err != nil {
		return "", err
	}
	var activity exportdomain.DirectoryActivity
	err = tx.QueryRowContext(ctx, `SELECT
 (SELECT COUNT(*) FROM tasks WHERE comic_id IN(SELECT value FROM json_each(:ids)) AND status IN('queued','running','cancelling')),
 (SELECT COUNT(*) FROM upload_sessions WHERE comic_id IN(SELECT value FROM json_each(:ids)) AND status IN('active','submitted','activated')),
 (SELECT COUNT(*) FROM lifecycle_runs WHERE comic_id IN(SELECT value FROM json_each(:ids)) AND completed=0),
 (SELECT COUNT(*) FROM metadata_refresh_runs WHERE comic_id IN(SELECT value FROM json_each(:ids)) AND applied=1 AND completed=0),
 (SELECT COUNT(*) FROM video_transcode_items i JOIN tasks t ON t.id=i.task_id WHERE t.comic_id IN(SELECT value FROM json_each(:ids)) AND i.status='prepared'),
 (SELECT COUNT(*) FROM upload_retired r JOIN upload_sessions s ON s.task_id=r.task_id WHERE s.comic_id IN(SELECT value FROM json_each(:ids)) AND r.status='pending')`, sql.Named("ids", string(data))).Scan(&activity.Tasks, &activity.Uploads, &activity.LifecycleRuns, &activity.RefreshRuns, &activity.PreparedTranscodes, &activity.PendingRetired)
	if err != nil {
		return "", err
	}
	if err = exportdomain.DirectoryInactive(activity); err != nil {
		return "", err
	}
	return string(data), nil
}

func (repo *repository) DirectoryAdmission(expected exportdomain.DirectoryPreviewRecord, scope exportdomain.DirectoryAdmissionScope, tokenHash string, now time.Time) jobsports.Admission {
	return func(task string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			var record exportdomain.DirectoryPreviewRecord
			var created, expires string
			err := tx.QueryRowContext(ctx, "SELECT id,token_hash,plan_sha,created_at,expires_at,COALESCE(task_id,'') FROM directory_move_previews WHERE id=?", expected.ID).Scan(&record.ID, &record.TokenHash, &record.SHA256, &created, &expires, &record.TaskID)
			if errors.Is(err, sql.ErrNoRows) {
				return exportdomain.ErrConflict
			}
			if err != nil {
				return err
			}
			if record.CreatedAt, err = time.Parse(directoryConfirmationTime, created); err != nil {
				return exportdomain.ErrManifest
			}
			if record.ExpiresAt, err = time.Parse(directoryConfirmationTime, expires); err != nil {
				return exportdomain.ErrManifest
			}
			if record.SHA256 != expected.SHA256 {
				return exportdomain.ErrConflict
			}
			if time.Now().UTC().After(now) {
				now = time.Now().UTC()
			}
			if err = exportdomain.CheckDirectoryConfirmation(record, tokenHash, now, false); err != nil {
				return err
			}
			if exportdomain.ValidateDirectoryScope(scope) != nil || scope.PreviewID != record.ID || scope.PlanSHA256 != record.SHA256 {
				return exportdomain.ErrManifest
			}
			ids, err := directoryAdmissionVersions(ctx, tx, scope)
			if err != nil {
				return err
			}
			if _, err = tx.ExecContext(ctx, "INSERT INTO directory_move_runs(task_id,preview_id) VALUES(?,?)", task, record.ID); err != nil {
				return err
			}
			if _, err = tx.ExecContext(ctx, `INSERT INTO directory_move_term_locks(task_id,kind,term_id) SELECT DISTINCT ?,'categories',c.category_id FROM active_comics c WHERE c.id IN(SELECT value FROM json_each(?)) AND c.category_id IS NOT NULL`, task, ids); err != nil {
				return err
			}
			if _, err = tx.ExecContext(ctx, `INSERT INTO directory_move_term_locks(task_id,kind,term_id) SELECT DISTINCT ?,'tags',ct.tag_id FROM comic_tags ct JOIN active_comics c ON c.id=ct.comic_id WHERE c.id IN(SELECT value FROM json_each(?))`, task, ids); err != nil {
				return err
			}
			for index, identity := range scope.Inventories {
				result, err := tx.ExecContext(ctx, "UPDATE comics SET lifecycle_state='moving_out' WHERE id=? AND lifecycle_state='active'", identity.ComicID)
				if err != nil {
					return err
				}
				count, err := result.RowsAffected()
				if err != nil {
					return err
				}
				if count != 1 {
					return exportdomain.ErrConflict
				}
				var accepted int64
				if err = tx.QueryRowContext(ctx, "SELECT revision FROM comic_change_versions WHERE comic_id=?", identity.ComicID).Scan(&accepted); err != nil {
					return err
				}
				if _, err = tx.ExecContext(ctx, "INSERT INTO directory_move_members(task_id,position,comic_id,inventory_sha,accepted_revision) VALUES(?,?,?,?,?)", task, index+1, identity.ComicID, identity.SHA256, accepted); err != nil {
					return err
				}
			}
			_, err = tx.ExecContext(ctx, "UPDATE directory_move_previews SET task_id=? WHERE id=? AND task_id IS NULL", task, record.ID)
			return err
		}
	}
}
