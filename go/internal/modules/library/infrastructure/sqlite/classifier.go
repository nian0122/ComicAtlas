package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"strings"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

func enrichImport(ctx context.Context, tx *sql.Tx, comic *librarydomain.Comic) error {
	rows, err := tx.QueryContext(ctx, "SELECT id,name,version FROM categories ORDER BY name COLLATE COMIC_NATURAL,id")
	if err != nil {
		return err
	}
	// 以迭代快照交给分类规则，避免为了分层将全部分类缓存到内存。
	classificationErr := librarydomain.ClassifyImport(comic, func(yield func(librarydomain.Term) bool) {
		for rows.Next() {
			var term librarydomain.Term
			if err = rows.Scan(&term.ID, &term.Name, &term.Version); err != nil {
				return
			}
			if !yield(term) {
				return
			}
		}
		err = rows.Err()
	})
	rows.Close()
	if err != nil {
		return err
	}
	if classificationErr != nil {
		return classificationErr
	}
	for index := range comic.Tags {
		tag := &comic.Tags[index]
		err = tx.QueryRowContext(ctx, "SELECT id,name,version FROM tags WHERE name_key=?", strings.ToLower(tag.Name)).Scan(&tag.ID, &tag.Name, &tag.Version)
		if errors.Is(err, sql.ErrNoRows) {
			tag.ID, err = librarydomain.NewID("tag_")
			if err != nil {
				return err
			}
			tag.Version = 1
			_, err = tx.ExecContext(ctx, "INSERT INTO tags VALUES(?,?,?,1)", tag.ID, tag.Name, strings.ToLower(tag.Name))
		}
		if err != nil {
			return err
		}
	}
	return nil
}
