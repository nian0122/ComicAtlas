package sqlite

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"database/sql"
	"encoding/json"
)

// 与 ID、revision 读取共用同一读事务；聚合数据库事实，不加载逐页模型或读取文件。
func selectHQFacts(ctx context.Context, tx *sql.Tx, items []librarydomain.SelectedComic) error {
	ids := []string{}
	positions := map[string]int{}
	for n, item := range items {
		if item.Version > 0 {
			ids = append(ids, item.ID)
			positions[item.ID] = n
		}
	}
	if len(ids) == 0 {
		return nil
	}
	data, err := json.Marshal(ids)
	if err != nil {
		return err
	}
	rows, err := tx.QueryContext(ctx, `SELECT c.id,COUNT(m.id),COALESCE(SUM(m.hq_state='available'),0),COALESCE(SUM(m.hq_state='released'),0),
 COALESCE(SUM(m.hq_state='releasing'),0),COALESCE(SUM(m.hq_state='available' AND m.sha256=''),0),
 COALESCE(SUM(m.hq_state='available' AND (COALESCE(l.valid,0)=0)),0),
 COALESCE(SUM(CASE WHEN m.hq_state='available' THEN m.size ELSE 0 END),0),
 EXISTS(SELECT 1 FROM tasks t WHERE t.comic_id=c.id AND t.type IN('image_lq','hq_release','archive_export','video_transcode','media_upload','metadata_refresh') AND t.status IN('queued','running','cancelling')),
 EXISTS(SELECT 1 FROM upload_sessions u WHERE u.comic_id=c.id AND u.status IN('submitted','activated')),
 EXISTS(SELECT 1 FROM video_transcode_items v JOIN tasks t ON t.id=v.task_id WHERE t.comic_id=c.id AND v.status='prepared')
 FROM json_each(?) selected JOIN active_comics c ON c.id=selected.value LEFT JOIN active_chapters ch ON ch.comic_id=c.id
 LEFT JOIN active_media m ON m.chapter_id=ch.id AND m.media_type='image' LEFT JOIN image_lq l ON l.media_id=m.id GROUP BY c.id`, string(data))
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		var counts mediadomain.HQImageCounts
		var active, upload, prepared bool
		if err = rows.Scan(&id, &counts.Images, &counts.Available, &counts.Released, &counts.Releasing, &counts.MissingSourceSHA256, &counts.MissingLQ, &counts.OriginalBytes, &active, &upload, &prepared); err != nil {
			return err
		}
		preview := mediadomain.HQSelectionPreview(id, counts, active, upload, prepared)
		items[positions[id]].HQ = &preview
	}
	return rows.Err()
}
