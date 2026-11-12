package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"context"
	"database/sql"
)

func loadDirectoryStructure(ctx context.Context, tx *sql.Tx, id string, value *exportdomain.DirectoryInventory) error {
	rows, err := tx.QueryContext(ctx, "SELECT id,parent_id,title,sort_order FROM catalogs WHERE comic_id=? ORDER BY id LIMIT ?", id, exportdomain.MaxNodes+1)
	if err != nil {
		return err
	}
	for rows.Next() {
		var item librarydomain.Catalog
		if err = rows.Scan(&item.ID, &item.ParentID, &item.Title, &item.SortOrder); err != nil {
			rows.Close()
			return err
		}
		value.Catalogs = append(value.Catalogs, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	rows, err = tx.QueryContext(ctx, "SELECT id,catalog_id,title,global_order,sort_order,media_version,lifecycle_state FROM chapters WHERE comic_id=? ORDER BY global_order,id LIMIT ?", id, exportdomain.MaxNodes+1)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var item exportdomain.DirectoryChapter
		if err = rows.Scan(&item.ID, &item.CatalogID, &item.Title, &item.GlobalOrder, &item.SortOrder, &item.MediaVersion, &item.State); err != nil {
			return err
		}
		value.Chapters = append(value.Chapters, item)
	}
	return rows.Err()
}
