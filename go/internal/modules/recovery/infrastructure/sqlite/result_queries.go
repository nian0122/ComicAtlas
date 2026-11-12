package sqlite

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"database/sql"
)

func (repo *sqliteRepository) PendingCandidates(ctx context.Context, task, after string, limit int) ([]string, error) {
	if err := recoverydomain.ValidateDiscoveryPage(task, after, limit); err != nil {
		return nil, err
	}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	run, err := readDiscovery(ctx, tx, task)
	if err != nil {
		return nil, err
	}
	if err = recoverydomain.ValidateDiscoveryWrite(run); err != nil {
		return nil, err
	}
	if !run.Sealed {
		return nil, recoverydomain.ErrConflict
	}
	rows, err := tx.QueryContext(ctx, `SELECT c.comic_id FROM recovery_candidates c LEFT JOIN recovery_results r ON r.task_id=c.task_id AND r.comic_id=c.comic_id
 WHERE c.task_id=? AND c.comic_id>? AND (r.task_id IS NULL OR (r.status='error' AND r.task_attempt<?)) ORDER BY c.comic_id LIMIT ?`, task, after, run.Attempt, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := make([]string, 0, limit)
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	if err = rows.Err(); err != nil {
		return nil, err
	}
	if err = rows.Close(); err != nil {
		return nil, err
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return ids, nil
}

func (repo *sqliteRepository) Summary(ctx context.Context, task string) (summary recoverydomain.ResultSummary, err error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return summary, err
	}
	defer tx.Rollback()
	run, err := readDiscovery(ctx, tx, task)
	if err != nil {
		return summary, err
	}
	if !run.Sealed {
		return recoverydomain.ResultSummary{}, recoverydomain.ErrConflict
	}
	summary, err = readSummary(ctx, tx, run)
	if err != nil {
		return recoverydomain.ResultSummary{}, err
	}
	if err = tx.Commit(); err != nil {
		return recoverydomain.ResultSummary{}, err
	}
	return summary, nil
}

func readSummary(ctx context.Context, query rowReader, run recoverydomain.DiscoveryRun) (summary recoverydomain.ResultSummary, err error) {
	summary.Total = run.CandidateCount
	err = query.QueryRowContext(ctx, `SELECT COUNT(*),COALESCE(SUM(status='restored'),0),COALESCE(SUM(status='placeholder'),0),COALESCE(SUM(status='skipped'),0),COALESCE(SUM(status='empty'),0),COALESCE(SUM(status='error'),0) FROM recovery_results WHERE task_id=?`, run.TaskID).Scan(&summary.Processed, &summary.Restored, &summary.Placeholders, &summary.Skipped, &summary.Empty, &summary.Failed)
	return summary, err
}
