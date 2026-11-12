package sqlite

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
)

func (repo *sqliteRepository) RecoveryCompletion(task string, attempt int) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		run, err := readDiscovery(ctx, tx, task)
		if err != nil {
			return err
		}
		summary, err := readSummary(ctx, tx, run)
		if err != nil {
			return err
		}
		var completed int
		var total sql.NullInt64
		if err = tx.QueryRowContext(ctx, "SELECT completed_count,total_count FROM tasks WHERE id=?", task).Scan(&completed, &total); err != nil {
			return err
		}
		if !total.Valid || total.Int64 != int64(run.CandidateCount) {
			return recoverydomain.ErrConflict
		}
		return recoverydomain.ValidateRecoveryCompletion(run, summary, attempt, completed)
	}
}
