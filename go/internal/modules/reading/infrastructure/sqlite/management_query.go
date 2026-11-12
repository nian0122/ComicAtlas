package sqlite

import (
	readingdomain "comicatlas.local/backend/internal/modules/reading/domain"
	"context"
	"database/sql"
	"strings"
)

// 仅管理评价元数据从稀疏标记表按主键访问隐藏对象，不读文件路径或阅读锚点。
const managedComicSource = `SELECT 'comic' target_type,r.target_id,r.reaction,r.updated_at,c.id comic_id,c.title comic_title,NULL chapter_id,NULL page_number,c.title title,NULL media_type,c.lifecycle_state target_state,c.lifecycle_state comic_state,'' chapter_state FROM comic_reactions r CROSS JOIN comics c ON c.id=r.target_id`
const managedChapterSource = `SELECT 'chapter' target_type,r.target_id,r.reaction,r.updated_at,c.id comic_id,c.title comic_title,ch.id chapter_id,NULL page_number,ch.title title,NULL media_type,ch.lifecycle_state target_state,c.lifecycle_state comic_state,ch.lifecycle_state chapter_state FROM chapter_reactions r CROSS JOIN chapters ch ON ch.id=r.target_id CROSS JOIN comics c ON c.id=ch.comic_id`
const managedMediaSource = `SELECT 'media' target_type,r.target_id,r.reaction,r.updated_at,c.id comic_id,c.title comic_title,ch.id chapter_id,m.page_number,m.file_name title,m.media_type,m.lifecycle_state target_state,c.lifecycle_state comic_state,ch.lifecycle_state chapter_state FROM media_reactions r CROSS JOIN media m ON m.id=r.target_id CROSS JOIN chapters ch ON ch.id=m.chapter_id CROSS JOIN comics c ON c.id=ch.comic_id`

func managedSource(kind string) string {
	queries := []string{}
	if kind == "" || kind == "comic" {
		queries = append(queries, managedComicSource)
	}
	if kind == "" || kind == "chapter" {
		queries = append(queries, managedChapterSource)
	}
	if kind == "" || kind == "media" {
		queries = append(queries, managedMediaSource)
	}
	return "(" + strings.Join(queries, " UNION ALL ") + ") r"
}

func managedFilter(query readingdomain.ManagementQuery) (string, []any) {
	where, args := " WHERE 1=1", []any{}
	if query.Value != "" {
		where += " AND r.reaction=?"
		args = append(args, query.Value)
	}
	if query.MediaType != "" {
		where += " AND r.media_type=?"
		args = append(args, query.MediaType)
	}
	if !query.IncludeTrashed {
		where += " AND r.target_state='active' AND r.comic_state='active' AND r.chapter_state IN('','active')"
	}
	return where, args
}

func managedPageQuery(query readingdomain.ManagementQuery) (string, []any) {
	where, args := managedFilter(query)
	order := "DESC"
	if query.Oldest {
		order = "ASC"
	}
	return `SELECT r.target_type,r.target_id,r.reaction,r.updated_at,r.comic_id,r.comic_title,r.chapter_id,r.page_number,r.title,r.media_type,r.target_state,r.comic_state,r.chapter_state FROM ` + managedSource(query.TargetType) + where + " ORDER BY r.updated_at " + order + ",r.target_type,r.target_id LIMIT ? OFFSET ?", append(args, query.PageSize, (query.Page-1)*query.PageSize)
}

func (repo *sqliteRepository) ManagedReactions(ctx context.Context, query readingdomain.ManagementQuery) (readingdomain.ManagedReactionPage, error) {
	value := readingdomain.ManagedReactionPage{Items: []readingdomain.ManagedReaction{}, Page: query.Page, PageSize: query.PageSize}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return value, err
	}
	defer tx.Rollback()
	where, args := managedFilter(query)
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM "+managedSource(query.TargetType)+where, args...).Scan(&value.Total); err != nil {
		return value, err
	}
	statement, args := managedPageQuery(query)
	rows, err := tx.QueryContext(ctx, statement, args...)
	if err != nil {
		return value, err
	}
	for rows.Next() {
		var item readingdomain.ManagedReaction
		var scope readingdomain.ReactionScope
		if err = rows.Scan(&item.TargetType, &item.TargetID, &item.Value, &item.UpdatedAt, &item.ComicID, &item.ComicTitle, &item.ChapterID, &item.PageNumber, &item.Title, &item.MediaType, &scope.Target, &scope.Comic, &scope.Chapter); err != nil {
			rows.Close()
			return value, err
		}
		value.Items = append(value.Items, readingdomain.ProjectManagedReaction(item, scope))
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}
