package sqlite

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"context"
	"database/sql"
	"encoding/json"
)

func comicFilterSQL(filter librarydomain.ComicFilter) (string, []any) {
	where := " WHERE 1=1"
	args := []any{}
	if filter.Keyword != "" {
		// 来源非 NULL（原始标题以空串补齐），下界覆盖全部文本并扫描预归一化索引。
		// 一元 + 避免按命中 ID 回表后全量排序，允许外层沿排序索引分页；两侧 ID 均为 TEXT。
		where += " AND +c.id IN(SELECT id FROM active_comics WHERE COMIC_LOWER(title||' '||COALESCE(original_title,'')||' '||author)>='' AND instr(COMIC_LOWER(title||' '||COALESCE(original_title,'')||' '||author),COMIC_LOWER(?))>0)"
		args = append(args, filter.Keyword)
	}
	if filter.Reaction != "" {
		where += " AND c.id IN(SELECT target_id FROM comic_reactions"
		if filter.Reaction != "any" {
			where += " WHERE reaction=?"
			args = append(args, filter.Reaction)
		}
		where += ")"
	}
	if filter.CategoryID != "" {
		where += " AND c.category_id=?"
		args = append(args, filter.CategoryID)
	}
	if filter.TagID != "" {
		where += " AND EXISTS(SELECT 1 FROM comic_tags ct WHERE ct.comic_id=c.id AND ct.tag_id=?)"
		args = append(args, filter.TagID)
	}
	if filter.ReadStatus == "read" {
		where += " AND EXISTS(SELECT 1 FROM reading_progress p WHERE p.comic_id=c.id)"
	}
	if filter.ReadStatus == "unread" {
		where += " AND NOT EXISTS(SELECT 1 FROM reading_progress p WHERE p.comic_id=c.id)"
	}
	if filter.ReadStatus == "reading" || filter.ReadStatus == "completed" {
		// 完成要求锚点之后既没有章节，也没有本章媒体；空的后续章节同样不能视作已完成。
		complete := `EXISTS(SELECT 1 FROM reading_progress p JOIN active_media anchor ON anchor.id=p.media_id JOIN active_chapters current ON current.id=anchor.chapter_id WHERE p.comic_id=c.id AND NOT EXISTS(SELECT 1 FROM active_chapters later WHERE later.comic_id=c.id AND later.global_order>current.global_order) AND NOT EXISTS(SELECT 1 FROM active_media later WHERE later.chapter_id=anchor.chapter_id AND later.page_number>anchor.page_number))`
		if filter.ReadStatus == "reading" {
			where += " AND EXISTS(SELECT 1 FROM reading_progress p WHERE p.comic_id=c.id) AND NOT " + complete
		} else {
			where += " AND " + complete
		}
	}
	return where, args
}
func (repo *sqliteRepository) SelectComics(ctx context.Context, selection librarydomain.ComicSelection) ([]librarydomain.SelectedComic, error) {
	return repo.selectComics(ctx, selection, false)
}
func (repo *sqliteRepository) SelectHQComics(ctx context.Context, selection librarydomain.ComicSelection) ([]librarydomain.SelectedComic, error) {
	return repo.selectComics(ctx, selection, true)
}
func (repo *sqliteRepository) selectComics(ctx context.Context, selection librarydomain.ComicSelection, hq bool) ([]librarydomain.SelectedComic, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var rows *sql.Rows
	selectionFacts := ",COALESCE((SELECT revision FROM comic_sidecars WHERE comic_id=c.id),0),(SELECT COUNT(*) FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=c.id AND m.media_type='image')"
	selectionFacts += ",(SELECT COUNT(*) FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=c.id AND m.media_type='video')"
	if selection.Mode == "ids" {
		data, _ := json.Marshal(selection.IDs)
		rows, err = tx.QueryContext(ctx, "SELECT j.value,COALESCE(c.title,''),COALESCE(c.version,0)"+selectionFacts+" FROM json_each(?) j LEFT JOIN active_comics c ON c.id=j.value ORDER BY j.value", string(data))
	} else {
		where, args := comicFilterSQL(*selection.Filter)
		excluded, _ := json.Marshal(selection.ExcludedIDs)
		where += " AND c.id NOT IN(SELECT value FROM json_each(?) WHERE type='text')"
		args = append(args, string(excluded), librarydomain.MaxSelectedComics+1)
		rows, err = tx.QueryContext(ctx, "SELECT c.id,c.title,c.version"+selectionFacts+" FROM active_comics c"+where+" ORDER BY c.id LIMIT ?", args...)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []librarydomain.SelectedComic{}
	for rows.Next() {
		var item librarydomain.SelectedComic
		if err = rows.Scan(&item.ID, &item.Title, &item.Version, &item.ScopeRevision, &item.Images, &item.Videos); err != nil {
			return nil, err
		}
		result = append(result, item)
	}
	if err = rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	if len(result) > librarydomain.MaxSelectedComics {
		return nil, librarydomain.ErrValidation
	}
	if hq {
		if err = selectHQFacts(ctx, tx, result); err != nil {
			return nil, err
		}
	}
	return result, tx.Commit()
}
