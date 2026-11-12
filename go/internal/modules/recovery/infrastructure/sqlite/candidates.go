package sqlite

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"database/sql"
)

func (repo *sqliteRepository) Candidates(ctx context.Context, task, after string, limit int) ([]string, error) {
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
	if !run.Sealed {
		return nil, recoverydomain.ErrConflict
	}
	rows, err := tx.QueryContext(ctx, "SELECT comic_id FROM recovery_candidates WHERE task_id=? AND comic_id>? ORDER BY comic_id LIMIT ?", task, after, limit)
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
