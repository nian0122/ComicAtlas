package sqlite

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
	"time"
)

func sidecarCapacity(ctx context.Context, tx *sql.Tx, comic, task string) error {
	busy, err := sidecarBlocked(ctx, tx, comic, task)
	if err != nil {
		return err
	}
	if busy {
		return storagedomain.ErrConflict
	}
	var count int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE type='metadata_sync' AND id<>? AND status IN('queued','running','cancelling')", task).Scan(&count); err != nil {
		return err
	}
	return storagedomain.ValidateSidecarCapacity(count)
}

func (repo *repository) SidecarAdmission(comic string, force bool) jobsports.Admission {
	return func(task string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			if err := sidecarCapacity(ctx, tx, comic, task); err != nil {
				return err
			}
			var revision, published int64
			if err := tx.QueryRowContext(ctx, "SELECT s.revision,s.published_revision FROM comic_sidecars s JOIN active_comics c ON c.id=s.comic_id WHERE s.comic_id=?", comic).Scan(&revision, &published); errors.Is(err, sql.ErrNoRows) {
				return storagedomain.ErrNotFound
			} else if err != nil {
				return err
			}
			if force {
				if _, err := tx.ExecContext(ctx, "UPDATE comic_sidecars SET revision=revision+1,requested_at=? WHERE comic_id=?", time.Now().UTC().Format(jobsdomain.TimestampFormat), comic); err != nil {
					return err
				}
				revision++
			} else if revision <= published {
				return storagedomain.ErrConflict
			}
			if _, err := tx.ExecContext(ctx, "INSERT INTO metadata_sync_runs(task_id,comic_id,requested_revision) VALUES(?,?,?)", task, comic, revision); err != nil {
				return err
			}
			_, err := tx.ExecContext(ctx, "UPDATE comic_sidecars SET last_task_id=? WHERE comic_id=?", task, comic)
			return err
		}
	}
}

func (repo *repository) SidecarRetry(task string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var comic string
		if err := tx.QueryRowContext(ctx, "SELECT s.comic_id FROM comic_sidecars s JOIN active_comics c ON c.id=s.comic_id JOIN metadata_sync_runs r ON r.task_id=s.last_task_id WHERE s.last_task_id=? AND r.published=0", task).Scan(&comic); errors.Is(err, sql.ErrNoRows) {
			return storagedomain.ErrConflict
		} else if err != nil {
			return err
		}
		return sidecarCapacity(ctx, tx, comic, task)
	}
}

func (repo *repository) SidecarResult(ctx context.Context, task string) (storagedomain.SidecarResult, error) {
	var value storagedomain.SidecarResult
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT task_id,comic_id,requested_revision,observed_revision,observed_at,published FROM metadata_sync_runs WHERE task_id=?", task).Scan(&value.TaskID, &value.ComicID, &value.RequestedRevision, &value.ObservedRevision, &value.ObservedAt, &value.Published)
	if errors.Is(err, sql.ErrNoRows) {
		err = storagedomain.ErrNotFound
	}
	return value, err
}

func (repo *repository) PrepareSidecar(ctx context.Context, task string, revision int64, hash, observed string) error {
	result, err := repo.database.Writer.ExecContext(ctx, `UPDATE metadata_sync_runs SET observed_revision=?,observed_at=?,sidecar_sha=?
 WHERE task_id=? AND published=0 AND EXISTS(SELECT 1 FROM comic_sidecars s JOIN active_comics c ON c.id=s.comic_id JOIN tasks t ON t.id=s.last_task_id
 WHERE s.last_task_id=metadata_sync_runs.task_id AND s.revision>=? AND t.status='running')`, revision, observed, hash, task, revision)
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

func (repo *repository) SidecarCompletion(task, hash string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var comic, expected string
		var revision int64
		if err := tx.QueryRowContext(ctx, "SELECT comic_id,observed_revision,sidecar_sha FROM metadata_sync_runs WHERE task_id=? AND published=0", task).Scan(&comic, &revision, &expected); err != nil {
			return err
		}
		if expected != hash {
			return storagedomain.ErrConflict
		}
		result, err := tx.ExecContext(ctx, `UPDATE comic_sidecars SET published_revision=?,published_at=? WHERE comic_id=? AND last_task_id=? AND revision>=? AND published_revision<=?
 AND EXISTS(SELECT 1 FROM active_comics WHERE id=?)`, revision, time.Now().UTC().Format(jobsdomain.TimestampFormat), comic, task, revision, revision, comic)
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
		_, err = tx.ExecContext(ctx, "UPDATE metadata_sync_runs SET published=1 WHERE task_id=?", task)
		return err
	}
}
