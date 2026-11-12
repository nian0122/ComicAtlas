package sqlite

import (
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
)

// NormalizeStructure 供组装层组合到生命周期短事务，复用同一结构排序规则和 SQL。
func NormalizeStructure(comic string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		tree, err := readStructure(ctx, tx, comic, true)
		if err != nil {
			return err
		}
		if err = persistStructure(ctx, tx, comic, tree); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE comics SET structure_version=structure_version+1 WHERE id=?", comic)
		return err
	}
}

func NormalizeMedia(chapter string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var version int
		if err := tx.QueryRowContext(ctx, "SELECT media_version FROM active_chapters WHERE id=?", chapter).Scan(&version); err != nil {
			return err
		}
		rows, err := tx.QueryContext(ctx, "SELECT id FROM active_media WHERE chapter_id=? ORDER BY page_number,id LIMIT 10001", chapter)
		if err != nil {
			return err
		}
		ids := []string{}
		for rows.Next() {
			var id string
			if err = rows.Scan(&id); err != nil {
				rows.Close()
				return err
			}
			ids = append(ids, id)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		_, err = reorderMedia(ctx, tx, chapter, version, ids)
		return err
	}
}
