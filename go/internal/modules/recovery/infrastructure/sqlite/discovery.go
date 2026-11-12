package sqlite

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	recoveryports "comicatlas.local/backend/internal/modules/recovery/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"

	"github.com/mattn/go-sqlite3"
)

var _ recoveryports.DiscoveryRepository = (*sqliteRepository)(nil)

func (repo *sqliteRepository) DiscoveryAdmission() jobsports.Admission {
	return func(task string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			result, err := tx.ExecContext(ctx, "INSERT INTO recovery_runs(task_id) SELECT id FROM tasks WHERE id=? AND type='disk_recovery' AND status='queued'", task)
			if err != nil {
				return err
			}
			count, err := result.RowsAffected()
			if err != nil {
				return err
			}
			if count != 1 {
				return recoverydomain.ErrConflict
			}
			return nil
		}
	}
}

func readDiscovery(ctx context.Context, query rowReader, task string) (run recoverydomain.DiscoveryRun, err error) {
	if !recoverydomain.Key(task) {
		return run, recoverydomain.ErrValidation
	}
	run.TaskID = task
	err = query.QueryRowContext(ctx, `SELECT r.sealed,r.candidate_count,r.ignored_count,t.status,t.attempt FROM recovery_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND t.type='disk_recovery'`, task).Scan(&run.Sealed, &run.CandidateCount, &run.IgnoredCount, &run.TaskStatus, &run.Attempt)
	if errors.Is(err, sql.ErrNoRows) {
		err = recoverydomain.ErrMissing
	}
	if err != nil {
		run = recoverydomain.DiscoveryRun{}
	}
	return run, err
}

func (repo *sqliteRepository) Discovery(ctx context.Context, task string) (recoverydomain.DiscoveryRun, error) {
	return readDiscovery(ctx, repo.database.Reader, task)
}

func (repo *sqliteRepository) BeginDiscovery(ctx context.Context, task string) (run recoverydomain.DiscoveryRun, err error) {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return run, err
	}
	defer tx.Rollback()
	run, err = readDiscovery(ctx, tx, task)
	if err != nil {
		return run, err
	}
	if err = recoverydomain.ValidateDiscoveryWrite(run); err != nil {
		return recoverydomain.DiscoveryRun{}, err
	}
	if !run.Sealed {
		if _, err = tx.ExecContext(ctx, "DELETE FROM recovery_candidates WHERE task_id=?", task); err != nil {
			return recoverydomain.DiscoveryRun{}, err
		}
		if _, err = tx.ExecContext(ctx, "UPDATE recovery_runs SET candidate_count=0,ignored_count=0 WHERE task_id=?", task); err != nil {
			return recoverydomain.DiscoveryRun{}, err
		}
		if _, err = tx.ExecContext(ctx, "UPDATE tasks SET stage='scanning',total_count=NULL,completed_count=0 WHERE id=?", task); err != nil {
			return recoverydomain.DiscoveryRun{}, err
		}
		run.CandidateCount, run.IgnoredCount = 0, 0
	} else {
		if _, err = tx.ExecContext(ctx, "UPDATE tasks SET stage='recovering',total_count=? WHERE id=?", run.CandidateCount, task); err != nil {
			return recoverydomain.DiscoveryRun{}, err
		}
	}
	if err = tx.Commit(); err != nil {
		return recoverydomain.DiscoveryRun{}, err
	}
	return run, nil
}

func (repo *sqliteRepository) AppendDiscovery(ctx context.Context, task string, ids []string) (err error) {
	defer func() {
		var constraint sqlite3.Error
		if errors.As(err, &constraint) && constraint.Code == sqlite3.ErrConstraint {
			err = errors.Join(recoverydomain.ErrConflict, err)
		}
	}()
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	run, err := readDiscovery(ctx, tx, task)
	if err != nil {
		return err
	}
	if err = recoverydomain.ValidateDiscoveryBatch(run, ids); err != nil {
		return err
	}
	statement, err := tx.PrepareContext(ctx, "INSERT INTO recovery_candidates(task_id,comic_id) VALUES(?,?)")
	if err != nil {
		return err
	}
	defer statement.Close()
	for _, id := range ids {
		if _, err = statement.ExecContext(ctx, task, id); err != nil {
			return err
		}
	}
	if _, err = tx.ExecContext(ctx, "UPDATE recovery_runs SET candidate_count=candidate_count+? WHERE task_id=?", len(ids), task); err != nil {
		return err
	}
	return tx.Commit()
}

func (repo *sqliteRepository) SealDiscovery(ctx context.Context, task string, found recoverydomain.Discovery) (run recoverydomain.DiscoveryRun, err error) {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return run, err
	}
	defer tx.Rollback()
	run, err = readDiscovery(ctx, tx, task)
	if err != nil {
		return run, err
	}
	var count int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM recovery_candidates WHERE task_id=?", task).Scan(&count); err != nil {
		return recoverydomain.DiscoveryRun{}, err
	}
	if err = recoverydomain.ValidateDiscoverySeal(run, found, count); err != nil {
		return recoverydomain.DiscoveryRun{}, err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE recovery_runs SET sealed=1,ignored_count=? WHERE task_id=?", found.Ignored, task); err != nil {
		return recoverydomain.DiscoveryRun{}, err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE tasks SET stage='recovering',total_count=? WHERE id=?", count, task); err != nil {
		return recoverydomain.DiscoveryRun{}, err
	}
	if err = tx.Commit(); err != nil {
		return recoverydomain.DiscoveryRun{}, err
	}
	run.Sealed, run.IgnoredCount = true, found.Ignored
	return run, nil
}
