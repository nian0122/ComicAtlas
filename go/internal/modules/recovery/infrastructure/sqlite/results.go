package sqlite

import (
	recoveryports "comicatlas.local/backend/internal/modules/recovery/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
)

var _ recoveryports.ResultRepository = (*sqliteRepository)(nil)

func readOutcome(ctx context.Context, query rowReader, task, comic string) (*recoverydomain.Outcome, error) {
	value := &recoverydomain.Outcome{TaskID: task, ComicID: comic}
	err := query.QueryRowContext(ctx, "SELECT status,task_attempt,try_count,error_code,source_sha,updated_at FROM recovery_results WHERE task_id=? AND comic_id=?", task, comic).Scan(&value.Status, &value.TaskAttempt, &value.TryCount, &value.ErrorCode, &value.SourceSHA256, &value.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return value, nil
}

func (repo *sqliteRepository) CommitOutcome(ctx context.Context, value recoverydomain.Outcome, write persistence.WriteAction) (result recoverydomain.Outcome, err error) {
	defer func() {
		err = errors.Join(err, ctx.Err())
		if err != nil {
			result = recoverydomain.Outcome{}
		}
	}()
	if err = recoverydomain.ValidateOutcome(value, write != nil); err != nil {
		return result, err
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	run, err := readDiscovery(ctx, tx, value.TaskID)
	if err != nil {
		return result, err
	}
	var member bool
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM recovery_candidates WHERE task_id=? AND comic_id=?)", value.TaskID, value.ComicID).Scan(&member); err != nil {
		return result, err
	}
	if !member {
		return result, recoverydomain.ErrMissing
	}
	previous, err := readOutcome(ctx, tx, value.TaskID, value.ComicID)
	if err != nil {
		return result, err
	}
	commit, err := recoverydomain.ShouldCommitOutcome(run, previous, value)
	if err != nil {
		return result, err
	}
	if !commit {
		return *previous, nil
	}
	if write != nil {
		if _, err = tx.ExecContext(ctx, "SAVEPOINT recovery_book"); err != nil {
			return result, err
		}
		if writeErr := write(ctx, tx); writeErr != nil {
			if ctx.Err() != nil {
				return result, errors.Join(writeErr, ctx.Err())
			}
			status, code, handled := recoverydomain.WriteFailure(writeErr)
			if !handled {
				return result, writeErr
			}
			if _, err = tx.ExecContext(ctx, "ROLLBACK TO recovery_book"); err != nil {
				return result, errors.Join(writeErr, err)
			}
			value.Status, value.ErrorCode = status, code
		}
		if _, err = tx.ExecContext(ctx, "RELEASE recovery_book"); err != nil {
			return result, err
		}
	}
	value.TryCount = 1
	delta := 1
	if previous != nil {
		value.TryCount = previous.TryCount + 1
		delta = 0
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO recovery_results(task_id,comic_id,status,task_attempt,try_count,error_code,source_sha,updated_at) VALUES(?,?,?,?,?,?,?,?)
 ON CONFLICT(task_id,comic_id) DO UPDATE SET status=excluded.status,task_attempt=excluded.task_attempt,try_count=excluded.try_count,error_code=excluded.error_code,source_sha=excluded.source_sha,updated_at=excluded.updated_at`, value.TaskID, value.ComicID, value.Status, value.TaskAttempt, value.TryCount, value.ErrorCode, value.SourceSHA256, value.UpdatedAt); err != nil {
		return result, err
	}
	updated, err := tx.ExecContext(ctx, "UPDATE tasks SET completed_count=completed_count+?,stage='recovering',total_count=? WHERE id=? AND type='disk_recovery' AND status='running' AND attempt=? AND completed_count+?<=?", delta, run.CandidateCount, value.TaskID, value.TaskAttempt, delta, run.CandidateCount)
	if err != nil {
		return result, err
	}
	count, err := updated.RowsAffected()
	if err != nil {
		return result, err
	}
	if count != 1 {
		return result, recoverydomain.ErrConflict
	}
	if err = tx.Commit(); err != nil {
		return result, err
	}
	return value, nil
}
