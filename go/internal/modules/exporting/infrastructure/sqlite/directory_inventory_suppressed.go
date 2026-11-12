package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
	"database/sql"
)

func loadDirectorySuppressed(ctx context.Context, tx *sql.Tx, id string, value *exportdomain.DirectoryInventory) error {
	rows, err := tx.QueryContext(ctx, "SELECT relative_path FROM comic_suppressed_paths WHERE comic_id=? ORDER BY relative_path LIMIT ?", id, exportdomain.MaxDirectoryFiles+1)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var name string
		if err = rows.Scan(&name); err != nil {
			return err
		}
		value.SuppressedPaths = append(value.SuppressedPaths, name)
		if len(value.SuppressedPaths) > exportdomain.MaxDirectoryFiles {
			return exportdomain.ErrValidation
		}
	}
	return rows.Err()
}
