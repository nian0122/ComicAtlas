package sqlite

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"context"
	"database/sql"
)

// 所有节点共用一个只读快照，不加载完整媒体模型或解码文件。
func (service *sqliteRepository) Previews(ctx context.Context, comicID string, nodes []librarydomain.NodeRef) ([]librarydomain.NodePreview, error) {
	tx, err := service.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var exists bool
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM active_comics WHERE id=?)", comicID).Scan(&exists); err != nil {
		return nil, err
	}
	if !exists {
		return nil, librarydomain.ErrNotFound
	}
	items := make([]librarydomain.NodePreview, 0, len(nodes))
	for _, node := range nodes {
		table := "active_chapters"
		if node.Type == "catalog" {
			table = "active_catalogs"
		}
		if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM "+table+" WHERE comic_id=? AND id=?)", comicID, node.ID).Scan(&exists); err != nil {
			return nil, err
		}
		if !exists {
			return nil, librarydomain.ErrNotFound
		}
		const prefix = `WITH RECURSIVE descendants(id) AS (
 SELECT id FROM active_catalogs WHERE comic_id=? AND id=?
 UNION SELECT c.id FROM active_catalogs c JOIN descendants d ON c.parent_id=d.id WHERE c.comic_id=?
), selected AS (
 SELECT m.id,m.media_type,ch.global_order,m.page_number FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id
 WHERE ch.comic_id=? AND ((?='chapter' AND ch.id=?) OR (?='catalog' AND ch.catalog_id IN(SELECT id FROM descendants)))
) `
		args := []any{comicID, node.ID, comicID, comicID, node.Type, node.ID, node.Type}
		item := librarydomain.NodePreview{NodeRef: node, Images: []librarydomain.PreviewImage{}}
		if err = tx.QueryRowContext(ctx, prefix+"SELECT COUNT(*) FROM selected", args...).Scan(&item.MediaCount); err != nil {
			return nil, err
		}
		rows, queryErr := tx.QueryContext(ctx, prefix+"SELECT id FROM selected WHERE media_type='image' ORDER BY global_order,page_number,id LIMIT 3", args...)
		if queryErr != nil {
			return nil, queryErr
		}
		for rows.Next() {
			var image librarydomain.PreviewImage
			if err = rows.Scan(&image.MediaID); err != nil {
				rows.Close()
				return nil, err
			}
			item.Images = append(item.Images, image)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, tx.Commit()
}
