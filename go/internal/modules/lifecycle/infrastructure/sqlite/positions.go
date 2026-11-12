package sqlite

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"database/sql"
	"math"
)

func (repo *repository) park(ctx context.Context, tx *sql.Tx, value lifecycledomain.Target) error {
	if value.Type == "comic" {
		return nil
	}
	query := "SELECT COALESCE(MAX(global_order),0) FROM chapters WHERE comic_id=?"
	parent := value.ComicID
	column := "global_order"
	if value.Type == "media" {
		query = "SELECT COALESCE(MAX(page_number),0) FROM media WHERE chapter_id=?"
		parent = value.ChapterID
		column = "page_number"
	}
	var maximum int64
	if err := tx.QueryRowContext(ctx, query, parent).Scan(&maximum); err != nil {
		return err
	}
	if maximum >= math.MaxInt64-1000001 {
		return lifecycledomain.ErrConflict
	}
	if _, err := tx.ExecContext(ctx, "UPDATE "+targetTable(value.Type)+" SET "+column+"=? WHERE id=?", max(maximum+1, 1000001), value.ID); err != nil {
		return err
	}
	if value.Type == "media" {
		return repo.media(value.ChapterID)(ctx, tx)
	}
	return repo.structure(value.ComicID)(ctx, tx)
}

func (repo *repository) reactivate(ctx context.Context, tx *sql.Tx, value lifecycledomain.Target) error {
	active, err := ancestorActive(ctx, tx, value)
	if err != nil {
		return err
	}
	if !active {
		return lifecycledomain.ErrConflict
	}
	maximum, err := restoreCapacity(ctx, tx, value)
	if err != nil {
		return err
	}
	if value.Type == "media" {
		if _, err = tx.ExecContext(ctx, "UPDATE media SET page_number=? WHERE id=?", maximum+1, value.ID); err != nil {
			return err
		}
	}
	if value.Type == "chapter" {
		// 追加到保留的原目录末尾，不覆盖在回收期间新建或调整的顺序。
		var order int
		if err = tx.QueryRowContext(ctx, `SELECT COALESCE(MAX(sort_order),0) FROM (
 SELECT sort_order FROM active_chapters WHERE comic_id=? AND catalog_id IS (SELECT catalog_id FROM chapters WHERE id=?)
 UNION ALL SELECT sort_order FROM active_catalogs WHERE comic_id=? AND parent_id IS (SELECT catalog_id FROM chapters WHERE id=?))`, value.ComicID, value.ID, value.ComicID, value.ID).Scan(&order); err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, "UPDATE chapters SET sort_order=? WHERE id=?", order+1, value.ID); err != nil {
			return err
		}
	}
	if _, err = tx.ExecContext(ctx, "UPDATE "+targetTable(value.Type)+" SET lifecycle_state='active' WHERE id=?", value.ID); err != nil {
		return err
	}
	if value.Type == "media" {
		return repo.media(value.ChapterID)(ctx, tx)
	}
	if value.Type == "chapter" {
		return repo.structure(value.ComicID)(ctx, tx)
	}
	return nil
}

func restoreCapacity(ctx context.Context, tx *sql.Tx, value lifecycledomain.Target) (int, error) {
	var maximum, count int
	if value.Type == "media" {
		if err := tx.QueryRowContext(ctx, "SELECT COALESCE(MAX(page_number),0),COUNT(*) FROM active_media WHERE chapter_id=?", value.ChapterID).Scan(&maximum, &count); err != nil {
			return 0, err
		}
	}
	if value.Type == "chapter" {
		if err := tx.QueryRowContext(ctx, "SELECT (SELECT COUNT(*) FROM active_chapters WHERE comic_id=?)+(SELECT COUNT(*) FROM active_catalogs WHERE comic_id=?)", value.ComicID, value.ComicID).Scan(&count); err != nil {
			return 0, err
		}
	}
	return maximum, lifecycledomain.ValidateRestoreCapacity(value.Type, count, maximum)
}
