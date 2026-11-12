package sqlite

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"database/sql"
	"errors"
)

// 生命周期读原记录以定位自己的非活动对象；普通查询仍只能使用活动视图。
func target(ctx context.Context, reader interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}, kind, id string) (lifecycledomain.Target, error) {
	value := lifecycledomain.Target{Type: kind, ID: id}
	query := "SELECT id,'',title,lifecycle_state FROM comics WHERE id=?"
	if kind == "chapter" {
		query = "SELECT comic_id,id,title,lifecycle_state FROM chapters WHERE id=?"
	}
	if kind == "media" {
		query = "SELECT ch.comic_id,m.chapter_id,m.file_name,m.lifecycle_state FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=?"
	}
	if err := lifecycledomain.ValidateTarget(kind, id); err != nil {
		return value, err
	}
	err := reader.QueryRowContext(ctx, query, id).Scan(&value.ComicID, &value.ChapterID, &value.Title, &value.State)
	if errors.Is(err, sql.ErrNoRows) {
		err = lifecycledomain.ErrNotFound
	}
	return value, err
}

func (repo *repository) Target(ctx context.Context, kind, id string) (lifecycledomain.Target, error) {
	return target(ctx, repo.database.Reader, kind, id)
}

func ancestorActive(ctx context.Context, tx *sql.Tx, value lifecycledomain.Target) (bool, error) {
	if value.Type == "comic" {
		return true, nil
	}
	query := "SELECT EXISTS(SELECT 1 FROM active_comics WHERE id=?)"
	id := value.ComicID
	if value.Type == "media" {
		query = "SELECT EXISTS(SELECT 1 FROM active_chapters WHERE id=?)"
		id = value.ChapterID
	}
	var active bool
	err := tx.QueryRowContext(ctx, query, id).Scan(&active)
	return active, err
}

func targetTable(kind string) string {
	if kind == "comic" {
		return "comics"
	}
	if kind == "chapter" {
		return "chapters"
	}
	return "media"
}
