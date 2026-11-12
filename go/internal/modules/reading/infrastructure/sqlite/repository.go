package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	readingports "comicatlas.local/backend/internal/modules/reading/application/ports"
	readingdomain "comicatlas.local/backend/internal/modules/reading/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

type sqliteRepository struct{ database *persistence.Database }

func New(database *persistence.Database) *sqliteRepository {
	return &sqliteRepository{database: database}
}

var _ readingports.Repository = (*sqliteRepository)(nil)

func targetTables(kind string) (target, reaction string, err error) {
	switch kind {
	case "comic":
		return "active_comics", "comic_reactions", nil
	case "chapter":
		return "active_chapters", "chapter_reactions", nil
	case "media":
		return "active_media", "media_reactions", nil
	default:
		return "", "", librarydomain.ErrValidation
	}
}

func reaction(ctx context.Context, tx *sql.Tx, kind, id string) (readingdomain.Reaction, error) {
	result := readingdomain.Reaction{TargetType: kind, TargetID: id, Value: "none"}
	target, _, err := targetTables(kind)
	if err != nil {
		return result, err
	}
	var found string
	if err = tx.QueryRowContext(ctx, "SELECT id FROM "+target+" WHERE id=?", id).Scan(&found); errors.Is(err, sql.ErrNoRows) {
		return result, librarydomain.ErrNotFound
	} else if err != nil {
		return result, err
	}
	return storedReaction(ctx, tx, kind, id)
}

func storedReaction(ctx context.Context, tx *sql.Tx, kind, id string) (readingdomain.Reaction, error) {
	result := readingdomain.Reaction{TargetType: kind, TargetID: id, Value: "none"}
	_, table, err := targetTables(kind)
	if err != nil {
		return result, err
	}
	err = tx.QueryRowContext(ctx, "SELECT reaction,updated_at FROM "+table+" WHERE target_id=?", id).Scan(&result.Value, &result.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		err = nil
	}
	return result, err
}

func writeReaction(ctx context.Context, tx *sql.Tx, change readingdomain.ReactionChange, now string) error {
	_, table, err := targetTables(change.TargetType)
	if err != nil {
		return err
	}
	if change.Value == "none" {
		_, err = tx.ExecContext(ctx, "DELETE FROM "+table+" WHERE target_id=?", change.TargetID)
	} else {
		_, err = tx.ExecContext(ctx, "INSERT INTO "+table+" VALUES(?,?,?) ON CONFLICT(target_id) DO UPDATE SET reaction=excluded.reaction,updated_at=excluded.updated_at WHERE reaction<>excluded.reaction", change.TargetID, change.Value, now)
	}
	return err
}

func (service *sqliteRepository) Reaction(ctx context.Context, kind, id string) (readingdomain.Reaction, error) {
	tx, err := service.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return readingdomain.Reaction{}, err
	}
	defer tx.Rollback()
	result, err := reaction(ctx, tx, kind, id)
	if err != nil {
		return result, err
	}
	return result, tx.Commit()
}

func (service *sqliteRepository) SetReactions(ctx context.Context, changes []readingdomain.ReactionChange) ([]readingdomain.Reaction, error) {
	tx, err := service.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	result := make([]readingdomain.Reaction, 0, len(changes))
	now := time.Now().UTC().Format("2006-01-02T15:04:05.000000000Z")
	for _, change := range changes {
		if _, err = reaction(ctx, tx, change.TargetType, change.TargetID); err != nil {
			return nil, err
		}
		err = writeReaction(ctx, tx, change, now)
		if err != nil {
			return nil, err
		}
		saved, err := reaction(ctx, tx, change.TargetType, change.TargetID)
		if err != nil {
			return nil, err
		}
		result = append(result, saved)
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return result, nil
}

// CROSS JOIN 固定从稀疏评价开始查主键，避免活动视图让优化器改成扫描整库媒体。
func reactionSource(kind string) string {
	queries := []string{}
	if kind == "" || kind == "comic" {
		queries = append(queries, `SELECT 'comic' AS target_type,r.target_id,r.reaction,r.updated_at,c.id AS comic_id,c.title AS comic_title,NULL AS chapter_id,NULL AS page_number,c.title AS title,NULL AS media_type FROM comic_reactions r CROSS JOIN active_comics c ON c.id=r.target_id`)
	}
	if kind == "" || kind == "chapter" {
		queries = append(queries, `SELECT 'chapter' AS target_type,r.target_id,r.reaction,r.updated_at,c.id AS comic_id,c.title AS comic_title,ch.id AS chapter_id,NULL AS page_number,ch.title AS title,NULL AS media_type FROM chapter_reactions r CROSS JOIN active_chapters ch ON ch.id=r.target_id CROSS JOIN active_comics c ON c.id=ch.comic_id`)
	}
	if kind == "" || kind == "media" {
		queries = append(queries, `SELECT 'media' AS target_type,r.target_id,r.reaction,r.updated_at,c.id AS comic_id,c.title AS comic_title,ch.id AS chapter_id,m.page_number,m.file_name AS title,m.media_type FROM media_reactions r CROSS JOIN active_media m ON m.id=r.target_id CROSS JOIN active_chapters ch ON ch.id=m.chapter_id CROSS JOIN active_comics c ON c.id=ch.comic_id`)
	}
	return "(" + strings.Join(queries, " UNION ALL ") + ") r"
}

func reactionPageQuery(kind, order string) string {
	// 左联接含多层 JOIN 的媒体视图会展开整表，锚点改为同一查询内的有索引标量读取。
	return `SELECT r.target_type,r.target_id,r.reaction,r.updated_at,r.comic_id,r.comic_title,r.chapter_id,r.page_number,r.title,r.media_type,
	 (SELECT anchor.chapter_id FROM active_media anchor WHERE anchor.id=p.media_id),
	 (SELECT anchor.page_number FROM active_media anchor WHERE anchor.id=p.media_id)
	 FROM ` + reactionSource(kind) + ` LEFT JOIN reading_progress p ON p.comic_id=r.comic_id
	 WHERE (?='' OR r.reaction=?) ORDER BY r.updated_at ` + order + `,r.target_type,r.target_id LIMIT ? OFFSET ?`
}

const historySource = ` FROM reading_progress p CROSS JOIN active_media m ON m.id=p.media_id CROSS JOIN active_chapters ch ON ch.id=m.chapter_id CROSS JOIN active_comics c ON c.id=ch.comic_id AND c.id=p.comic_id`

func (service *sqliteRepository) Reactions(ctx context.Context, kind, value string, page, size int, oldest bool) (readingdomain.ReactionPage, error) {
	result := readingdomain.ReactionPage{Items: []readingdomain.Favorite{}, Page: page, PageSize: size}
	tx, err := service.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	from := " FROM " + reactionSource(kind)
	where := " WHERE (?='' OR r.reaction=?)"
	arguments := []any{value, value}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*)"+from+where, arguments...).Scan(&result.Total); err != nil {
		return result, err
	}
	order := "DESC"
	if oldest {
		order = "ASC"
	}
	rows, err := tx.QueryContext(ctx, reactionPageQuery(kind, order), append(arguments, size, (page-1)*size)...)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var item readingdomain.Favorite
		if err = rows.Scan(&item.TargetType, &item.TargetID, &item.Value, &item.UpdatedAt, &item.ComicID, &item.ComicTitle, &item.ChapterID, &item.PageNumber, &item.Title, &item.MediaType, &item.LastReadChapterID, &item.LastReadPageNumber); err != nil {
			rows.Close()
			return result, err
		}
		if item.TargetType == "media" {
			url := "/api/v1/media/" + item.TargetID + "/content"
			item.ContentURL = &url
		}
		result.Items = append(result.Items, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	return result, tx.Commit()
}

func (service *sqliteRepository) History(ctx context.Context, page, size int) (readingdomain.HistoryPage, error) {
	result := readingdomain.HistoryPage{Items: []readingdomain.HistoryEntry{}, Page: page, PageSize: size}
	tx, err := service.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	from := historySource
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*)"+from).Scan(&result.Total); err != nil {
		return result, err
	}
	rows, err := tx.QueryContext(ctx, `SELECT c.id,c.title,ch.id,ch.title,m.page_number,m.id,p.updated_at,
 NOT EXISTS(SELECT 1 FROM active_chapters later WHERE later.comic_id=c.id AND later.global_order>ch.global_order),
 NOT EXISTS(SELECT 1 FROM active_media later WHERE later.chapter_id=ch.id AND later.page_number>m.page_number)`+from+" ORDER BY p.updated_at DESC,c.id LIMIT ? OFFSET ?", size, (page-1)*size)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var item readingdomain.HistoryEntry
		var lastChapter, lastPage bool
		if err = rows.Scan(&item.ComicID, &item.Title, &item.ChapterID, &item.ChapterTitle, &item.PageNumber, &item.MediaID, &item.UpdatedAt, &lastChapter, &lastPage); err != nil {
			rows.Close()
			return result, err
		}
		item.ContentURL = "/api/v1/media/" + item.MediaID + "/content"
		item.Completed = readingdomain.ReadingCompleted(lastChapter, lastPage)
		result.Items = append(result.Items, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	return result, tx.Commit()
}
