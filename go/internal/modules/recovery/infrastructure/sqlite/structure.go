package sqlite

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"context"
	"database/sql"
)

func restoreStructure(ctx context.Context, tx *sql.Tx, snapshot librarydomain.Snapshot) error {
	catalogs, err := tx.PrepareContext(ctx, "INSERT INTO catalogs(id,comic_id,parent_id,title,sort_order) VALUES(?,?,?,?,?)")
	if err != nil {
		return err
	}
	defer catalogs.Close()
	for _, node := range snapshot.Detail.Catalogs {
		if _, err = catalogs.ExecContext(ctx, node.ID, snapshot.Detail.ID, node.ParentID, node.Title, node.SortOrder); err != nil {
			return err
		}
	}
	chapters, err := tx.PrepareContext(ctx, "INSERT INTO chapters(id,comic_id,catalog_id,title,global_order,sort_order,media_version) VALUES(?,?,?,?,?,?,?)")
	if err != nil {
		return err
	}
	defer chapters.Close()
	for _, chapter := range snapshot.Detail.Chapters {
		if _, err = chapters.ExecContext(ctx, chapter.ID, snapshot.Detail.ID, chapter.CatalogID, chapter.Title, chapter.GlobalOrder, chapter.SortOrder, chapter.MediaVersion); err != nil {
			return err
		}
	}
	return nil
}
