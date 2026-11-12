package sqlite

import (
	"context"
	"database/sql"

	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
)

func blockImportSources(ctx context.Context, tx *sql.Tx, source, exclude string) error {
	rows, err := tx.QueryContext(ctx, `SELECT source_path FROM tasks WHERE id<>? AND source_path<>'' AND status IN('queued','running','cancelling')
 UNION ALL SELECT source_path FROM import_source_claims WHERE task_id<>?`, exclude, exclude)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var current string
		if err = rows.Scan(&current); err != nil {
			return err
		}
		if jobsdomain.ImportSourcesOverlap(source, current) {
			return jobsdomain.ErrConflict
		}
	}
	return rows.Err()
}

func blockReservedImportSources(ctx context.Context, tx *sql.Tx, source, batch, task string, position int) error {
	rows, err := tx.QueryContext(ctx, `SELECT i.source_path FROM import_batch_dispatch_items i
 JOIN task_batches b ON b.id=i.batch_id JOIN tasks p ON p.id=b.parent_task_id
 WHERE i.status IN('pending','dispatched') AND p.status IN('queued','running','cancelling')
 AND NOT(i.batch_id=? AND i.position=?) AND (?='' OR COALESCE(i.task_id,'')<>?)`, batch, position, task, task)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var current string
		if err = rows.Scan(&current); err != nil {
			return err
		}
		if jobsdomain.ImportSourcesOverlap(source, current) {
			return jobsdomain.ErrConflict
		}
	}
	return rows.Err()
}

func (store *sqliteRepository) ClaimImportSource(ctx context.Context, id string) error {
	tx, err := store.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var source, status, kind string
	if err = tx.QueryRowContext(ctx, "SELECT source_path,status,type FROM tasks WHERE id=?", id).Scan(&source, &status, &kind); err != nil {
		return err
	}
	if source == "" || status != "running" || kind != "directory_import" && kind != "archive_import" {
		return jobsdomain.ErrConflict
	}
	if err = blockImportSources(ctx, tx, source, id); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO import_source_claims(task_id,source_path) VALUES(?,?) ON CONFLICT(task_id) DO NOTHING", id, source); err != nil {
		return err
	}
	return tx.Commit()
}
