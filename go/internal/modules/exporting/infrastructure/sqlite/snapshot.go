package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

func loadSnapshot(ctx context.Context, tx *sql.Tx, id string, request exportdomain.Request) (exportdomain.Snapshot, error) {
	result := exportdomain.Snapshot{Version: 1, Request: request, Catalogs: []librarydomain.Catalog{}, Chapters: []librarydomain.Chapter{}, Sources: []exportdomain.Source{}}
	comic := &result.Comic
	var categoryID, categoryName *string
	var categoryVersion *int
	err := tx.QueryRowContext(ctx, `SELECT c.id,c.title,c.created_at,c.author,c.description,c.original_title,c.version,c.category_name,t.id,t.name,t.version FROM active_comics c LEFT JOIN categories t ON t.id=c.category_id WHERE c.id=?`, id).Scan(&comic.ID, &comic.Title, &comic.CreatedAt, &comic.Author, &comic.Description, &comic.OriginalTitle, &comic.Version, &comic.CategoryName, &categoryID, &categoryName, &categoryVersion)
	if errors.Is(err, sql.ErrNoRows) {
		return result, exportdomain.ErrNotFound
	}
	if err != nil {
		return result, err
	}
	if categoryID != nil {
		comic.Category = &librarydomain.Term{ID: *categoryID, Name: *categoryName, Version: *categoryVersion}
	}
	comic.Tags = []librarydomain.ComicTag{}
	rows, err := tx.QueryContext(ctx, "SELECT t.id,t.name,t.version,ct.source FROM comic_tags ct JOIN tags t ON t.id=ct.tag_id WHERE ct.comic_id=? ORDER BY t.name COLLATE COMIC_NATURAL,t.id LIMIT 101", id)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var tag librarydomain.ComicTag
		if err = rows.Scan(&tag.ID, &tag.Name, &tag.Version, &tag.Source); err != nil {
			rows.Close()
			return result, err
		}
		comic.Tags = append(comic.Tags, tag)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	if len(comic.Tags) > 100 {
		return result, exportdomain.ErrValidation
	}
	var source string
	err = tx.QueryRowContext(ctx, "SELECT comicinfo_json FROM comic_source_metadata WHERE comic_id=?", id).Scan(&source)
	if err == nil {
		result.ComicInfo = &librarydomain.ComicInfo{}
		if json.Unmarshal([]byte(source), result.ComicInfo) != nil {
			return result, exportdomain.ErrValidation
		}
	} else if !errors.Is(err, sql.ErrNoRows) {
		return result, err
	}
	rows, err = tx.QueryContext(ctx, "SELECT id,parent_id,title,sort_order FROM active_catalogs AS catalogs WHERE comic_id=? ORDER BY id LIMIT ?", id, exportdomain.MaxNodes+1)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var node librarydomain.Catalog
		if err = rows.Scan(&node.ID, &node.ParentID, &node.Title, &node.SortOrder); err != nil {
			rows.Close()
			return result, err
		}
		result.Catalogs = append(result.Catalogs, node)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	rows, err = tx.QueryContext(ctx, "SELECT id,catalog_id,title,global_order,sort_order,media_version FROM active_chapters AS chapters WHERE comic_id=? ORDER BY global_order,id LIMIT ?", id, exportdomain.MaxNodes+1)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var node librarydomain.Chapter
		if err = rows.Scan(&node.ID, &node.CatalogID, &node.Title, &node.GlobalOrder, &node.SortOrder, &node.MediaVersion); err != nil {
			rows.Close()
			return result, err
		}
		result.Chapters = append(result.Chapters, node)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	rows, err = tx.QueryContext(ctx, `SELECT m.id,m.chapter_id,m.page_number,m.file_name,m.media_type,m.relative_path,m.sha256,m.size,m.hq_state,COALESCE(l.relative_path,''),COALESCE(l.sha256,''),COALESCE(l.size,0),COALESCE(l.valid=1,0) FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id LEFT JOIN image_lq l ON l.media_id=m.id WHERE ch.comic_id=? ORDER BY ch.global_order,m.page_number,m.id LIMIT ?`, id, exportdomain.MaxMedia+1)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var value exportdomain.Candidate
		s := &value.Source
		if err = rows.Scan(&s.MediaID, &s.ChapterID, &s.PageNumber, &s.FileName, &s.MediaType, &s.Path, &s.SHA256, &s.Size, &value.HQState, &value.LQPath, &value.LQSHA, &value.LQSize, &value.LQCurrent); err != nil {
			rows.Close()
			return result, err
		}
		selected, selectErr := exportdomain.SelectSource(value)
		if selectErr != nil {
			rows.Close()
			return result, selectErr
		}
		result.Sources = append(result.Sources, selected)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	var progress librarydomain.Progress
	progress.ComicID = id
	err = tx.QueryRowContext(ctx, "SELECT p.chapter_id,p.page_number,p.updated_at,COALESCE(p.media_id,'') FROM reading_progress p JOIN active_chapters ch ON ch.id=p.chapter_id AND ch.comic_id=p.comic_id JOIN active_media m ON m.id=p.media_id AND m.chapter_id=p.chapter_id WHERE p.comic_id=?", id).Scan(&progress.ChapterID, &progress.PageNumber, &progress.UpdatedAt, &progress.MediaID)
	if err == nil {
		result.Progress = &progress
	} else if !errors.Is(err, sql.ErrNoRows) {
		return result, err
	}
	return result, exportdomain.ValidateSnapshot(result)
}
