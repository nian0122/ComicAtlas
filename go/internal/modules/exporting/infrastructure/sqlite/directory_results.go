package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
	"database/sql"
	"errors"
)

func scanDirectoryResult(row interface{ Scan(...any) error }) (exportdomain.DirectoryFileResult, error) {
	var value exportdomain.DirectoryFileResult
	err := row.Scan(&value.Position, &value.ComicID, &value.Source, &value.Target, &value.Size, &value.SHA256, &value.State)
	return value, err
}

const directoryResultColumns = "position,CASE WHEN length(CAST(comic_id AS BLOB))<=128 THEN comic_id ELSE '' END,CASE WHEN length(CAST(source AS BLOB))<=2048 THEN source ELSE '' END,CASE WHEN length(CAST(target AS BLOB))<=2048 THEN target ELSE '' END,size,CASE WHEN length(sha256)<=64 THEN sha256 ELSE '' END,state"

func (repo *repository) SaveDirectoryResults(ctx context.Context, id string, rows []exportdomain.DirectoryFileResult) error {
	if err := exportdomain.ValidateDirectoryResultBatch(rows); err != nil {
		return err
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var count int
	err = tx.QueryRowContext(ctx, `SELECT t.total_count FROM directory_move_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND r.completed=0 AND t.manifest IS NOT NULL AND t.status='running' AND t.type='directory_move'`, id).Scan(&count)
	if err != nil {
		return err
	}
	statement, err := tx.PrepareContext(ctx, "INSERT INTO directory_move_results(task_id,position,comic_id,source,target,size,sha256,state) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(task_id,position) DO NOTHING")
	if err != nil {
		return err
	}
	defer statement.Close()
	added := int64(0)
	for _, next := range rows {
		if exportdomain.ValidateDirectoryCompletedResult(next, next.Position) != nil || next.Position > count {
			return exportdomain.ErrManifest
		}
		result, err := statement.ExecContext(ctx, id, next.Position, next.ComicID, next.Source, next.Target, next.Size, next.SHA256, next.State)
		if err != nil {
			return err
		}
		n, err := result.RowsAffected()
		if err != nil {
			return err
		}
		if n == 0 {
			previous, err := scanDirectoryResult(tx.QueryRowContext(ctx, "SELECT "+directoryResultColumns+" FROM directory_move_results WHERE task_id=? AND position=?", id, next.Position))
			if err != nil {
				return err
			}
			if previous != next {
				return exportdomain.ErrManifest
			}
		}
		added += n
	}
	if added > 0 {
		if _, err = tx.ExecContext(ctx, "UPDATE tasks SET completed_count=completed_count+? WHERE id=? AND status='running'", added, id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (repo *repository) DirectoryResults(ctx context.Context, id string, page, size int) (exportdomain.DirectoryResultPage, error) {
	value := exportdomain.DirectoryResultPage{TaskID: id, Page: page, PageSize: size, Items: []exportdomain.DirectoryFileResult{}}
	if err := exportdomain.ValidateDirectoryResultQuery(id, page, size); err != nil {
		return value, err
	}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return value, err
	}
	defer tx.Rollback()
	err = tx.QueryRowContext(ctx, "SELECT t.status,r.completed FROM directory_move_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND t.type='directory_move'", id).Scan(&value.Status, &value.Complete)
	if errors.Is(err, sql.ErrNoRows) {
		return value, exportdomain.ErrNotFound
	}
	if err != nil {
		return value, err
	}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM directory_move_results WHERE task_id=?", id).Scan(&value.Total); err != nil {
		return value, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT "+directoryResultColumns+" FROM directory_move_results WHERE task_id=? ORDER BY position LIMIT ? OFFSET ?", id, size, (page-1)*size)
	if err != nil {
		return value, err
	}
	for rows.Next() {
		item, err := scanDirectoryResult(rows)
		if err != nil {
			rows.Close()
			return value, err
		}
		if exportdomain.ValidateDirectoryResult(item) != nil {
			rows.Close()
			return value, exportdomain.ErrManifest
		}
		value.Items = append(value.Items, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}
