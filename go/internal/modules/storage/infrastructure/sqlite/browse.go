package sqlite

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"context"
	"database/sql"
	"strings"
)

func (repo *repository) Browse(ctx context.Context, kind, parent string, query storagedomain.BrowseQuery) (storagedomain.StoragePage, error) {
	return repo.browse(ctx, kind, parent, "", query)
}
func (repo *repository) Object(ctx context.Context, kind, id string) (storagedomain.StorageObject, error) {
	page, err := repo.browse(ctx, kind, "", id, storagedomain.BrowseQuery{Page: 1, PageSize: 1})
	if err != nil {
		return storagedomain.StorageObject{}, err
	}
	if len(page.Items) == 0 {
		return storagedomain.StorageObject{}, storagedomain.ErrNotFound
	}
	return page.Items[0], nil
}

func (repo *repository) browse(ctx context.Context, kind, parent, selected string, query storagedomain.BrowseQuery) (storagedomain.StoragePage, error) {
	if kind == "comic" {
		return repo.browseComics(ctx, selected, query)
	}
	return repo.browseChapters(ctx, parent, selected, query)
}

// browseComics 读取物化指标；脏漫画在本次只读事务内即时聚合，不阻塞写入。
func (repo *repository) browseComics(ctx context.Context, selected string, query storagedomain.BrowseQuery) (storagedomain.StoragePage, error) {
	result := storagedomain.StoragePage{Items: []storagedomain.StorageObject{}, Page: query.Page, PageSize: query.PageSize}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	where, arguments := comicFilters(selected, query)
	column := comicSortColumn(query)
	order := "ASC"
	if query.Order == "desc" {
		order = "DESC"
	}
	rows, err := tx.QueryContext(ctx, "WITH "+metricsCombined()+" SELECT "+comicColumns+",COUNT(*) OVER() FROM active_comics c JOIN combined m ON m.comic_id=c.id"+where+
		" ORDER BY "+column+" "+order+",c.id ASC LIMIT ? OFFSET ?", append(arguments, query.PageSize, (query.Page-1)*query.PageSize)...)
	if err != nil {
		return result, err
	}
	if err = scanStorageRows("comic", rows, true, &result); err != nil {
		return result, err
	}
	if len(result.Items) == 0 {
		if err = tx.QueryRowContext(ctx, "WITH "+metricsCombined()+" SELECT COUNT(*) FROM active_comics c JOIN combined m ON m.comic_id=c.id"+where, arguments...).Scan(&result.Total); err != nil {
			return result, err
		}
	}
	if err = loadOperationFacts(ctx, tx, result.Items); err != nil {
		return result, err
	}
	return result, tx.Commit()
}

// browseChapters 保留按页聚合：章节范围受单本漫画限制，不会扫描全库。
func (repo *repository) browseChapters(ctx context.Context, parent, selected string, query storagedomain.BrowseQuery) (storagedomain.StoragePage, error) {
	result := storagedomain.StoragePage{Items: []storagedomain.StorageObject{}, Page: query.Page, PageSize: query.PageSize}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	if parent != "" {
		var count int
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM active_comics AS comics WHERE id=?", parent).Scan(&count); err != nil {
			return result, err
		}
		if count == 0 {
			return result, storagedomain.ErrNotFound
		}
	}
	objects, arguments := chapterObjects(parent, selected, query)
	column := chapterSortColumn(query)
	filter := chapterFilter(query)
	metricFilter := filter != ""
	metricSort := query.Sort == "totalBytes" || query.Sort == "hqBytes" || query.Sort == "lqBytes"
	order := "ASC"
	if query.Order == "desc" {
		order = "DESC"
	}
	ordering := " ORDER BY " + column + " " + order + ",o.id ASC"
	selectList := chapterColumns
	if metricFilter {
		selectList += ",COUNT(*) OVER()"
	}
	if metricFilter || metricSort {
		metrics := chapterMetrics("objects")
		if !metricFilter {
			if err = tx.QueryRowContext(ctx, "WITH objects AS ("+objects+") SELECT COUNT(*) FROM objects", arguments...).Scan(&result.Total); err != nil {
				return result, err
			}
		}
		rows, queryErr := tx.QueryContext(ctx, "WITH objects AS ("+objects+"), metrics AS ("+metrics+") SELECT "+selectList+
			" FROM objects o JOIN metrics m ON m.id=o.id"+filter+ordering+" LIMIT ? OFFSET ?",
			append(arguments, query.PageSize, (query.Page-1)*query.PageSize)...)
		if queryErr != nil {
			return result, queryErr
		}
		if err = scanStorageRows("chapter", rows, metricFilter, &result); err != nil {
			return result, err
		}
		if metricFilter && len(result.Items) == 0 {
			countSQL := "WITH objects AS (" + objects + "), metrics AS (" + metrics + ") SELECT COUNT(*) FROM objects o JOIN metrics m ON m.id=o.id" + filter
			if err = tx.QueryRowContext(ctx, countSQL, arguments...).Scan(&result.Total); err != nil {
				return result, err
			}
		}
	} else {
		if err = tx.QueryRowContext(ctx, "WITH objects AS ("+objects+") SELECT COUNT(*) FROM objects", arguments...).Scan(&result.Total); err != nil {
			return result, err
		}
		page := "SELECT o.id,o.comic_id,o.title,o.global_order FROM objects o" + ordering + " LIMIT ? OFFSET ?"
		metrics := chapterMetrics("page")
		rows, queryErr := tx.QueryContext(ctx, "WITH objects AS ("+objects+"), page AS ("+page+"), metrics AS ("+metrics+") SELECT "+selectList+
			" FROM page o LEFT JOIN metrics m ON m.id=o.id"+ordering,
			append(arguments, query.PageSize, (query.Page-1)*query.PageSize)...)
		if queryErr != nil {
			return result, queryErr
		}
		if err = scanStorageRows("chapter", rows, false, &result); err != nil {
			return result, err
		}
	}
	if err = loadOperationFacts(ctx, tx, result.Items); err != nil {
		return result, err
	}
	return result, tx.Commit()
}

func scanStorageRows(kind string, rows *sql.Rows, windowCount bool, result *storagedomain.StoragePage) error {
	defer rows.Close()
	for rows.Next() {
		var object storagedomain.StorageObject
		var total int
		inventory, images := &object.Inventory, &object.Facts.Images
		scanTargets := []any{&object.ID, &object.ComicID, &object.Title, &object.GlobalOrder, &inventory.Chapters, &inventory.Images, &inventory.Videos, &inventory.HQ.Files, &inventory.HQ.Bytes, &inventory.LQ.Files, &inventory.LQ.Bytes, &inventory.CurrentLQImages,
			&images.Available, &images.Released, &images.Releasing, &images.MissingSourceSHA256, &images.MissingLQ, &images.OriginalBytes, &inventory.Covers.Files, &inventory.Covers.Bytes}
		if windowCount {
			scanTargets = append(scanTargets, &total)
		}
		if err := rows.Scan(scanTargets...); err != nil {
			return err
		}
		inventory.ScopeType, inventory.ScopeID, inventory.Comics = kind, &object.ID, 1
		images.Images = int(inventory.Images)
		inventory.AvailableImages, inventory.AvailableBytes = int64(images.Available), images.OriginalBytes
		inventory.MissingLQ, inventory.MissingSourceSHA256Images = int64(images.MissingLQ), int64(images.MissingSourceSHA256)
		inventory.ReleasedImages, inventory.ReleasingImages = int64(images.Released), int64(images.Releasing)
		result.Items = append(result.Items, object)
		if windowCount {
			result.Total = total
		}
	}
	return rows.Err()
}

const comicColumns = "c.id,c.id AS comic_id,c.title,NULL AS global_order,m.chapters,m.images,m.videos,m.hq_files,m.hq_bytes,m.lq_files,m.lq_bytes,m.current_lq," +
	"m.available_images,m.released_images,m.releasing_images,m.missing_source_sha256_images,m.missing_lq,m.available_bytes,m.cover_files,m.cover_bytes"

func tagFilterSQL(query storagedomain.BrowseQuery) (string, []any) {
	if query.TagMode == "none" {
		return " AND NOT EXISTS(SELECT 1 FROM comic_tags ct WHERE ct.comic_id=c.id)", nil
	}
	if len(query.TagIDs) == 0 {
		return "", nil
	}
	arguments := make([]any, 0, len(query.TagIDs))
	for _, id := range query.TagIDs {
		arguments = append(arguments, id)
	}
	switch query.TagMode {
	case "all":
		where := ""
		for range query.TagIDs {
			where += " AND EXISTS(SELECT 1 FROM comic_tags ct WHERE ct.comic_id=c.id AND ct.tag_id=?)"
		}
		return where, arguments
	case "not":
		return " AND NOT EXISTS(SELECT 1 FROM comic_tags ct WHERE ct.comic_id=c.id AND ct.tag_id IN (" + strings.TrimSuffix(strings.Repeat("?,", len(query.TagIDs)), ",") + "))", arguments
	case "any":
		return " AND EXISTS(SELECT 1 FROM comic_tags ct WHERE ct.comic_id=c.id AND ct.tag_id IN (" + strings.TrimSuffix(strings.Repeat("?,", len(query.TagIDs)), ",") + "))", arguments
	}
	return "", nil
}

func comicFilters(selected string, query storagedomain.BrowseQuery) (string, []any) {
	where := " WHERE instr(COMIC_LOWER(c.title||' '||COALESCE(c.original_title,'')||' '||c.author),COMIC_LOWER(?))>0"
	arguments := []any{query.Keyword}
	if selected != "" {
		where += " AND c.id=?"
		arguments = append(arguments, selected)
	}
	if query.Uncategorized {
		where += " AND c.category_id IS NULL"
	} else if query.CategoryID != "" {
		where += " AND c.category_id=?"
		arguments = append(arguments, query.CategoryID)
	}
	tagWhere, tagArguments := tagFilterSQL(query)
	where += tagWhere
	arguments = append(arguments, tagArguments...)
	switch query.HQStatus {
	case "has_hq":
		where += " AND m.hq_files>0"
	case "no_hq":
		where += " AND m.hq_files=0"
	}
	switch query.LQStatus {
	case "needs_lq":
		where += " AND m.images>m.current_lq"
	case "ready":
		where += " AND m.images>0 AND m.images=m.current_lq"
	}
	switch query.Attention {
	case "missing_lq":
		where += " AND m.missing_lq>0"
	case "can_release":
		where += " AND m.available_images>0"
	case "missing_source_sha256":
		where += " AND m.missing_source_sha256_images>0"
	}
	return where, arguments
}

func comicSortColumn(query storagedomain.BrowseQuery) string {
	column := "c.title COLLATE COMIC_NATURAL"
	switch query.Sort {
	case "createdAt":
		column = "c.created_at"
	case "pages":
		column = "m.images"
	case "totalBytes":
		column = "m.hq_bytes+m.lq_bytes+m.cover_bytes"
	case "hqBytes":
		column = "m.hq_bytes"
	case "lqBytes":
		column = "m.lq_bytes"
	}
	return column
}

func chapterObjects(parent, selected string, query storagedomain.BrowseQuery) (string, []any) {
	objects := "SELECT ch.id,ch.comic_id,ch.title,ch.global_order FROM active_chapters ch JOIN active_comics c ON c.id=ch.comic_id"
	objects += " WHERE instr(COMIC_LOWER(ch.title||' '||c.title||' '||COALESCE(c.original_title,'')||' '||c.author),COMIC_LOWER(?))>0"
	arguments := []any{query.Keyword}
	if parent != "" {
		objects += " AND c.id=?"
		arguments = append(arguments, parent)
	}
	if selected != "" {
		objects += " AND ch.id=?"
		arguments = append(arguments, selected)
	}
	if query.Uncategorized {
		objects += " AND c.category_id IS NULL"
	} else if query.CategoryID != "" {
		objects += " AND c.category_id=?"
		arguments = append(arguments, query.CategoryID)
	}
	tagWhere, tagArguments := tagFilterSQL(query)
	objects += tagWhere
	arguments = append(arguments, tagArguments...)
	return objects, arguments
}

func chapterMetrics(source string) string {
	return `SELECT o.id AS id,COUNT(DISTINCT o.id) AS chapters,
	 COALESCE(SUM(m.media_type='image'),0) AS images,COALESCE(SUM(m.media_type='video'),0) AS videos,
	 COALESCE(SUM(m.hq_state<>'released'),0) AS hq_files,COALESCE(SUM(CASE WHEN m.hq_state<>'released' THEN m.size ELSE 0 END),0) AS hq_bytes,
	 COUNT(l.media_id) AS lq_files,COALESCE(SUM(l.size),0) AS lq_bytes,COALESCE(SUM(l.valid=1),0) AS current_lq,
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='available'),0) AS available_images,
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='released'),0) AS released_images,
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='releasing'),0) AS releasing_images,
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='available' AND m.sha256=''),0) AS missing_source_sha256_images,
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='available' AND (l.media_id IS NULL OR l.valid<>1)),0) AS missing_lq,
	 COALESCE(SUM(CASE WHEN m.media_type='image' AND m.hq_state='available' THEN m.size ELSE 0 END),0) AS available_bytes,
	 0 AS cover_files,0 AS cover_bytes
	 FROM ` + source + ` o
	 LEFT JOIN active_media m ON m.chapter_id=o.id
	 LEFT JOIN image_lq l ON l.media_id=m.id
	 GROUP BY o.id`
}

const chapterColumns = "o.id,o.comic_id,o.title,o.global_order,m.chapters,m.images,m.videos,m.hq_files,m.hq_bytes,m.lq_files,m.lq_bytes,m.current_lq," +
	"m.available_images,m.released_images,m.releasing_images,m.missing_source_sha256_images,m.missing_lq,m.available_bytes,m.cover_files,m.cover_bytes"

func chapterSortColumn(query storagedomain.BrowseQuery) string {
	column := "o.global_order"
	if query.Sort == "title" {
		column = "o.title COLLATE COMIC_NATURAL"
	}
	switch query.Sort {
	case "totalBytes":
		column = "m.hq_bytes+m.lq_bytes+m.cover_bytes"
	case "hqBytes":
		column = "m.hq_bytes"
	case "lqBytes":
		column = "m.lq_bytes"
	case "globalOrder":
		column = "o.global_order"
	}
	return column
}

func chapterFilter(query storagedomain.BrowseQuery) string {
	filter := ""
	switch query.HQStatus {
	case "has_hq":
		filter += " AND m.hq_files>0"
	case "no_hq":
		filter += " AND m.hq_files=0"
	}
	switch query.LQStatus {
	case "needs_lq":
		filter += " AND m.images>m.current_lq"
	case "ready":
		filter += " AND m.images>0 AND m.images=m.current_lq"
	}
	if filter == "" {
		return ""
	}
	return " WHERE 1=1" + filter
}

func loadOperationFacts(ctx context.Context, tx *sql.Tx, items []storagedomain.StorageObject) error {
	if len(items) == 0 {
		return nil
	}
	byComic := map[string][]*storagedomain.StorageObject{}
	var ids []any
	for index := range items {
		item := &items[index]
		if _, found := byComic[item.ComicID]; !found {
			ids = append(ids, item.ComicID)
		}
		byComic[item.ComicID] = append(byComic[item.ComicID], item)
	}
	marks := strings.TrimSuffix(strings.Repeat("?,", len(ids)), ",")
	var active int
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE status IN ('queued','running','cancelling')").Scan(&active); err != nil {
		return err
	}
	for index := range items {
		items[index].Facts.ActiveTasks = active
	}
	rows, err := tx.QueryContext(ctx, `SELECT id,comic_id,type,target_type,target_id,status,stage FROM tasks WHERE type IN ('image_lq','hq_release','archive_export','video_transcode','media_upload','metadata_refresh') AND status IN ('queued','running','cancelling') AND comic_id IN (`+marks+`) ORDER BY created_at,id`, ids...)
	if err != nil {
		return err
	}
	for rows.Next() {
		var task jobsdomain.Task
		if err = rows.Scan(&task.ID, &task.ComicID, &task.Type, &task.TargetType, &task.TargetID, &task.Status, &task.Stage); err != nil {
			rows.Close()
			return err
		}
		for _, item := range byComic[task.ComicID] {
			item.Facts.Tasks = append(item.Facts.Tasks, task)
			item.Facts.ActiveMedia++
			if task.Type == "metadata_refresh" {
				item.Facts.ActiveMetadata++
			}
			if task.Type == "media_upload" {
				item.Facts.ActiveUpload++
			}
			if task.Type == "video_transcode" {
				item.Facts.ActiveTranscode++
			}
			if task.Type == "archive_export" {
				item.Facts.ActiveExport++
			}
			if task.Type == "hq_release" {
				item.Facts.ActiveHQ++
			}
			if task.Type == "image_lq" && task.TargetType == item.Inventory.ScopeType && task.TargetID == item.ID {
				item.Facts.ActiveLQ++
			}
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	rows, err = tx.QueryContext(ctx, `SELECT ch.comic_id,COUNT(*) FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.hq_state='releasing' AND ch.comic_id IN (`+marks+`) GROUP BY ch.comic_id`, ids...)
	if err != nil {
		return err
	}
	for rows.Next() {
		var comic string
		var count int
		if err = rows.Scan(&comic, &count); err != nil {
			rows.Close()
			return err
		}
		for _, item := range byComic[comic] {
			item.Facts.IncompleteRelease = count
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	rows, err = tx.QueryContext(ctx, `SELECT t.comic_id,COUNT(*) FROM video_transcode_items i JOIN tasks t ON t.id=i.task_id WHERE i.status='prepared' AND t.comic_id IN (`+marks+`) GROUP BY t.comic_id`, ids...)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var comic string
		var count int
		if err = rows.Scan(&comic, &count); err != nil {
			return err
		}
		for _, item := range byComic[comic] {
			item.Facts.IncompleteTranscode = count
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	rows, err = tx.QueryContext(ctx, `SELECT comic_id,COUNT(*) FROM upload_sessions WHERE status IN ('submitted','activated') AND comic_id IN (`+marks+`) GROUP BY comic_id`, ids...)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var comic string
		var count int
		if err = rows.Scan(&comic, &count); err != nil {
			return err
		}
		for _, item := range byComic[comic] {
			item.Facts.IncompleteUpload = count
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	rows, err = tx.QueryContext(ctx, `SELECT comic_id,COUNT(*) FROM metadata_refresh_runs WHERE applied=1 AND completed=0 AND comic_id IN (`+marks+`) GROUP BY comic_id`, ids...)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var comic string
		var count int
		if err = rows.Scan(&comic, &count); err != nil {
			return err
		}
		for _, item := range byComic[comic] {
			item.Facts.IncompleteMetadata = count
		}
	}
	return rows.Err()
}
