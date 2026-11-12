package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
	"database/sql"
)

// 先用长度/条数聚合约束数据库输出，再读取文本或解析 JSON；不拼接正文来计量。
func directoryInventoryBudget(ctx context.Context, tx *sql.Tx, id string) error {
	queries := []struct {
		query   string
		maximum int
	}{
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(title AS BLOB))+length(CAST(author AS BLOB))+length(CAST(description AS BLOB))+length(CAST(category_name AS BLOB))+COALESCE(length(CAST(original_title AS BLOB)),0)+length(CAST(id AS BLOB))+length(CAST(created_at AS BLOB))),0) FROM comics WHERE id=:comic`, 1},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(t.name AS BLOB))+length(CAST(t.id AS BLOB))+length(CAST(ct.source AS BLOB))),0) FROM comic_tags ct JOIN tags t ON t.id=ct.tag_id WHERE ct.comic_id=:comic`, 100},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(t.name AS BLOB))+length(CAST(t.id AS BLOB))),0) FROM comics c JOIN categories t ON t.id=c.category_id WHERE c.id=:comic`, 1},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(comicinfo_json AS BLOB))),0) FROM comic_source_metadata WHERE comic_id=:comic`, 1},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(relative_path AS BLOB))),0) FROM comic_suppressed_paths WHERE comic_id=:comic`, exportdomain.MaxDirectoryFiles},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(id AS BLOB))+COALESCE(length(CAST(parent_id AS BLOB)),0)+length(CAST(title AS BLOB))),0) FROM catalogs WHERE comic_id=:comic`, exportdomain.MaxNodes},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(id AS BLOB))+COALESCE(length(CAST(catalog_id AS BLOB)),0)+length(CAST(title AS BLOB))+length(CAST(lifecycle_state AS BLOB))),0) FROM chapters WHERE comic_id=:comic`, exportdomain.MaxNodes},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(m.id AS BLOB))+length(CAST(m.chapter_id AS BLOB))+length(CAST(m.file_name AS BLOB))+length(CAST(m.relative_path AS BLOB))+length(CAST(m.sha256 AS BLOB))+length(CAST(m.media_type AS BLOB))+length(CAST(m.content_type AS BLOB))+COALESCE(length(CAST(m.video_metadata AS BLOB)),0)+length(CAST(m.hq_state AS BLOB))+length(CAST(m.lifecycle_state AS BLOB))),0) FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=:comic`, exportdomain.MaxDirectoryFiles},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(l.media_id AS BLOB))+length(CAST(l.relative_path AS BLOB))+length(CAST(l.sha256 AS BLOB))+length(CAST(COALESCE(l.source_sha256,'') AS BLOB))+length(CAST(COALESCE(l.task_id,'') AS BLOB))+length(CAST(l.origin AS BLOB))+length(CAST(COALESCE(l.original_state,'') AS BLOB))+length(CAST(COALESCE(l.source_reference,'') AS BLOB))),0) FROM image_lq l JOIN media m ON m.id=l.media_id JOIN chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=:comic`, exportdomain.MaxDirectoryFiles},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(s.id AS BLOB))+length(CAST(s.replace_media_id AS BLOB))+length(CAST(r.kind AS BLOB))+length(CAST(r.sha256 AS BLOB))+length(CAST(r.path AS BLOB))+COALESCE(length(CAST(s.prior_json AS BLOB)),0)),0) FROM upload_retired r JOIN upload_sessions s ON s.task_id=r.task_id JOIN media m ON m.id=s.replace_media_id JOIN chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=:comic AND r.status='moved'`, exportdomain.MaxDirectoryFiles},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(id AS BLOB))+length(CAST(target_id AS BLOB))+length(CAST(chapter_id AS BLOB))+length(CAST(title AS BLOB))+length(CAST(plan_json AS BLOB))+length(CAST(task_id AS BLOB))+length(CAST(created_at AS BLOB))+length(CAST(updated_at AS BLOB))+length(CAST(state AS BLOB))),0) FROM lifecycle_entries WHERE comic_id=:comic AND state NOT IN('restored','purged','detached')`, exportdomain.MaxNodes},
		{`SELECT COALESCE(SUM(json_array_length(plan_json)),0),0 FROM lifecycle_entries WHERE comic_id=:comic AND state NOT IN('restored','purged','detached')`, exportdomain.MaxDirectoryFiles},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(r.target_id AS BLOB))+length(CAST(r.reaction AS BLOB))+length(CAST(r.updated_at AS BLOB))),0) FROM content_reactions r WHERE (r.target_type='comic' AND r.target_id=:comic) OR (r.target_type='chapter' AND r.target_id IN(SELECT id FROM chapters WHERE comic_id=:comic)) OR (r.target_type='media' AND r.target_id IN(SELECT m.id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=:comic))`, exportdomain.MaxDirectoryFiles + exportdomain.MaxNodes + 1},
		{`SELECT COUNT(*),COALESCE(SUM(length(CAST(chapter_id AS BLOB))+COALESCE(length(CAST(media_id AS BLOB)),0)+length(CAST(updated_at AS BLOB))),0) FROM reading_progress WHERE comic_id=:comic`, 1},
	}
	var total int64
	for _, item := range queries {
		var count, bytes int64
		if err := tx.QueryRowContext(ctx, item.query, sql.Named("comic", id)).Scan(&count, &bytes); err != nil {
			return err
		}
		if err := exportdomain.DirectoryInventoryBudget(count, bytes, total, item.maximum); err != nil {
			return err
		}
		total += bytes
	}
	return nil
}
