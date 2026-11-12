package sqlite

import (
	"context"
	"database/sql"
)

func retryTargetVisible(ctx context.Context, tx *sql.Tx, comic, kind, id string) (bool, error) {
	query := "SELECT EXISTS(SELECT 1 FROM active_comics WHERE id=? AND id=?)"
	args := []any{comic, id}
	switch kind {
	case "chapter":
		query = "SELECT EXISTS(SELECT 1 FROM active_chapters WHERE comic_id=? AND id=?)"
	case "media":
		query = "SELECT EXISTS(SELECT 1 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND m.id=?)"
	case "upload_session":
		query = `SELECT EXISTS(SELECT 1 FROM upload_sessions s JOIN active_chapters ch ON ch.id=s.chapter_id LEFT JOIN active_media m ON m.id=s.replace_media_id WHERE s.comic_id=? AND s.id=? AND (s.replace_media_id IS NULL OR m.id IS NOT NULL))`
	}
	var found bool
	err := tx.QueryRowContext(ctx, query, args...).Scan(&found)
	return found, err
}
