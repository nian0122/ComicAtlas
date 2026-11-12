package sqlite

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"database/sql"
)

func (repo *sqliteRepository) ResultPage(ctx context.Context, task, after string, limit int) (page recoverydomain.ResultPage, err error) {
	if err = recoverydomain.ValidateDiscoveryPage(task, after, limit); err != nil {
		return page, err
	}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return page, err
	}
	defer tx.Rollback()
	run, err := readDiscovery(ctx, tx, task)
	if err != nil {
		return page, err
	}
	page = recoverydomain.ResultPage{TaskID: task, Sealed: run.Sealed, CandidateCount: run.CandidateCount, IgnoredCount: run.IgnoredCount, Items: []recoverydomain.ResultItem{}}
	if run.Sealed {
		summary, readErr := readSummary(ctx, tx, run)
		if readErr != nil {
			return recoverydomain.ResultPage{}, readErr
		}
		page.Summary = &summary
		rows, readErr := tx.QueryContext(ctx, "SELECT comic_id,status,task_attempt,try_count,error_code,updated_at FROM recovery_results WHERE task_id=? AND comic_id>? ORDER BY comic_id LIMIT ?", task, after, limit+1)
		if readErr != nil {
			return recoverydomain.ResultPage{}, readErr
		}
		defer rows.Close()
		for rows.Next() {
			var item recoverydomain.ResultItem
			if err = rows.Scan(&item.ComicID, &item.Status, &item.Attempt, &item.TryCount, &item.ErrorCode, &item.UpdatedAt); err != nil {
				return recoverydomain.ResultPage{}, err
			}
			page.Items = append(page.Items, item)
		}
		if err = rows.Err(); err != nil {
			return recoverydomain.ResultPage{}, err
		}
		if err = rows.Close(); err != nil {
			return recoverydomain.ResultPage{}, err
		}
		if len(page.Items) > limit {
			page.Items = page.Items[:limit]
			next := page.Items[limit-1].ComicID
			page.NextAfter = &next
		}
	}
	if err = tx.Commit(); err != nil {
		return recoverydomain.ResultPage{}, err
	}
	return page, nil
}
