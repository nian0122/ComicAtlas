package sqlite

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

func (service *sqliteRepository) Search(ctx context.Context, query librarydomain.ListQuery) (librarydomain.Page, error) {
	page, size, keyword := query.Page, query.PageSize, query.Keyword
	result := librarydomain.Page{Items: []librarydomain.Comic{}, Page: page, PageSize: size}
	sortColumn := "c.title COLLATE COMIC_NATURAL"
	switch query.Sort {
	case "", "title":
	case "author":
		sortColumn = "c.author COLLATE COMIC_NATURAL"
	case "createdAt":
		sortColumn = "c.created_at"
	case "lastReadTime":
		sortColumn = "COALESCE((SELECT updated_at FROM reading_progress WHERE comic_id=c.id),'')"
	default:
		return result, librarydomain.ErrValidation
	}
	order := "ASC"
	switch query.Order {
	case "", "asc":
	case "desc":
		order = "DESC"
	default:
		return result, librarydomain.ErrValidation
	}
	filter := librarydomain.ComicFilter{Keyword: keyword, CategoryID: query.CategoryID, ReadStatus: query.ReadStatus}
	if err := librarydomain.ValidateComicFilter(filter); err != nil {
		return result, err
	}
	where, arguments := comicFilterSQL(filter)
	for _, id := range query.TagIDs {
		where += " AND EXISTS(SELECT 1 FROM comic_tags ct WHERE ct.comic_id=c.id AND ct.tag_id=?)"
		arguments = append(arguments, id)
	}

	tx, err := service.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM active_comics c"+where, arguments...).Scan(&result.Total); err != nil {
		return result, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT c.id,c.title,c.created_at,c.author,c.description,c.category_name,c.version,c.original_title FROM active_comics c"+where+" ORDER BY "+sortColumn+" "+order+",c.id LIMIT ? OFFSET ?", append(arguments, size, (page-1)*size)...)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var comic librarydomain.Comic
		if err = rows.Scan(&comic.ID, &comic.Title, &comic.CreatedAt, &comic.Author, &comic.Description, &comic.CategoryName, &comic.Version, &comic.OriginalTitle); err != nil {
			rows.Close()
			return result, err
		}
		result.Items = append(result.Items, comic)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	comics := make([]*librarydomain.Comic, 0, len(result.Items))
	for index := range result.Items {
		comics = append(comics, &result.Items[index])
	}
	if err = loadMetadata(ctx, tx, comics); err != nil {
		return result, err
	}
	return result, tx.Commit()
}

func (service *sqliteRepository) Detail(ctx context.Context, id string) (librarydomain.Detail, error) {
	tx, err := service.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return librarydomain.Detail{}, err
	}
	defer tx.Rollback()
	result, err := loadDetail(ctx, tx, id)
	if err != nil {
		return result, err
	}
	return result, tx.Commit()
}

func loadDetail(ctx context.Context, tx *sql.Tx, id string) (librarydomain.Detail, error) {
	result := librarydomain.Detail{Catalogs: []librarydomain.Catalog{}, Chapters: []librarydomain.Chapter{}}
	err := tx.QueryRowContext(ctx, "SELECT id,title,created_at,author,description,category_name,version,structure_version,original_title FROM active_comics AS comics WHERE id=?", id).Scan(&result.ID, &result.Title, &result.CreatedAt, &result.Author, &result.Description, &result.CategoryName, &result.Version, &result.StructureVersion, &result.OriginalTitle)
	if errors.Is(err, sql.ErrNoRows) {
		return result, librarydomain.ErrNotFound
	}
	if err != nil {
		return result, err
	}
	var source string
	sourceErr := tx.QueryRowContext(ctx, "SELECT comicinfo_json FROM comic_source_metadata WHERE comic_id=?", id).Scan(&source)
	if sourceErr == nil {
		result.ComicInfo = &librarydomain.ComicInfo{}
		if err = json.Unmarshal([]byte(source), result.ComicInfo); err != nil {
			return result, err
		}
	} else if !errors.Is(sourceErr, sql.ErrNoRows) {
		return result, sourceErr
	}

	if err = loadMetadata(ctx, tx, []*librarydomain.Comic{&result.Comic}); err != nil {
		return result, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT id,parent_id,title,sort_order FROM active_catalogs AS catalogs WHERE comic_id=? ORDER BY sort_order,id", id)
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
	rows, err = tx.QueryContext(ctx, "SELECT id,catalog_id,title,global_order,sort_order,media_version FROM active_chapters AS chapters WHERE comic_id=? ORDER BY global_order", id)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var chapter librarydomain.Chapter
		if err = rows.Scan(&chapter.ID, &chapter.CatalogID, &chapter.Title, &chapter.GlobalOrder, &chapter.SortOrder, &chapter.MediaVersion); err != nil {
			rows.Close()
			return result, err
		}
		result.Chapters = append(result.Chapters, chapter)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	return result, nil
}

const mediaColumns = "m.id,m.chapter_id,m.page_number,m.file_name,m.relative_path,m.media_type,m.content_type,m.size,m.width,m.height,m.sha256,m.video_metadata,COALESCE(l.source_sha256,''),l.width,l.height,l.size,l.valid,latest.task_id,lt.status,li.status,li.error_code,m.hq_state,CASE WHEN m.hq_state='released' THEN 'released' WHEN obs.source_sha256=m.sha256 THEN obs.status ELSE 'unknown' END,l.origin,l.source_reference,l.original_state,COALESCE(l.task_id,''),EXISTS(SELECT 1 FROM legacy_page_sources source WHERE source.media_id=m.id AND json_extract(source.source_json,'$.metadataOnly')=1)"
const mediaLQJoins = " LEFT JOIN media_observations obs ON obs.media_id=m.id LEFT JOIN image_lq l ON l.media_id=m.id LEFT JOIN image_lq_latest latest ON latest.media_id=m.id LEFT JOIN tasks lt ON lt.id=latest.task_id LEFT JOIN image_lq_items li ON li.task_id=latest.task_id AND li.media_id=m.id "

func (service *sqliteRepository) Media(ctx context.Context, id string) (librarydomain.Media, error) {
	item, err := scanMedia(service.database.Reader.QueryRowContext(ctx, "SELECT "+mediaColumns+" FROM active_media m"+mediaLQJoins+" WHERE m.id=?", id))
	if errors.Is(err, sql.ErrNoRows) {
		err = librarydomain.ErrNotFound
	}
	return item, err
}

func (service *sqliteRepository) Reader(ctx context.Context, id string) (librarydomain.Reader, error) {
	result := librarydomain.Reader{Items: []librarydomain.Media{}}
	tx, err := service.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	err = tx.QueryRowContext(ctx, "SELECT id,comic_id,catalog_id,title,global_order,sort_order,media_version FROM active_chapters AS chapters WHERE id=?", id).Scan(&result.Chapter.ID, &result.ComicID, &result.Chapter.CatalogID, &result.Chapter.Title, &result.Chapter.GlobalOrder, &result.Chapter.SortOrder, &result.Chapter.MediaVersion)
	if errors.Is(err, sql.ErrNoRows) {
		return result, librarydomain.ErrNotFound
	}
	if err != nil {
		return result, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT "+mediaColumns+" FROM active_media m"+mediaLQJoins+" WHERE m.chapter_id=? ORDER BY m.page_number", id)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		item, scanErr := scanMedia(rows)
		if scanErr != nil {
			rows.Close()
			return result, scanErr
		}
		result.Items = append(result.Items, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	for _, neighbor := range []struct {
		query  string
		target **string
	}{
		{"SELECT id FROM active_chapters AS chapters WHERE comic_id=? AND global_order<? ORDER BY global_order DESC LIMIT 1", &result.PreviousChapterID},
		{"SELECT id FROM active_chapters AS chapters WHERE comic_id=? AND global_order>? ORDER BY global_order LIMIT 1", &result.NextChapterID},
	} {
		var found string
		err = tx.QueryRowContext(ctx, neighbor.query, result.ComicID, result.Chapter.GlobalOrder).Scan(&found)
		if err == nil {
			*neighbor.target = &found
		} else if !errors.Is(err, sql.ErrNoRows) {
			return result, err
		}
	}
	return result, tx.Commit()
}

func (service *sqliteRepository) Progress(ctx context.Context, comicID string) (*librarydomain.Progress, error) {
	var result librarydomain.Progress
	err := service.database.Reader.QueryRowContext(ctx, `SELECT p.comic_id,m.chapter_id,m.page_number,p.updated_at,m.id FROM reading_progress p JOIN active_media m ON m.id=p.media_id JOIN active_chapters ch ON ch.id=m.chapter_id WHERE p.comic_id=? AND ch.comic_id=p.comic_id`, comicID).Scan(&result.ComicID, &result.ChapterID, &result.PageNumber, &result.UpdatedAt, &result.MediaID)
	if errors.Is(err, sql.ErrNoRows) {
		var exists int
		if err = service.database.Reader.QueryRowContext(ctx, "SELECT 1 FROM active_comics AS comics WHERE id=?", comicID).Scan(&exists); errors.Is(err, sql.ErrNoRows) {
			return nil, librarydomain.ErrNotFound
		}
		return nil, err
	}
	return &result, err
}

func (service *sqliteRepository) SaveProgress(ctx context.Context, comicID, chapterID string, page int) (librarydomain.Progress, error) {
	return service.savePosition(ctx, comicID, chapterID, "m.page_number=?", page)
}

// 写入时保存稳定媒体锚点，页码只作为当前定位缓存。
func (service *sqliteRepository) SaveMediaProgress(ctx context.Context, comicID, chapterID, mediaID string) (librarydomain.Progress, error) {
	return service.savePosition(ctx, comicID, chapterID, "m.id=?", mediaID)
}
func (service *sqliteRepository) savePosition(ctx context.Context, comicID, chapterID, where string, value any) (librarydomain.Progress, error) {
	var result librarydomain.Progress
	err := service.database.Writer.QueryRowContext(ctx, `INSERT INTO reading_progress(comic_id,chapter_id,page_number,updated_at,media_id)
 SELECT ch.comic_id,ch.id,m.page_number,?,m.id FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.id=? AND ch.comic_id=? AND `+where+`
 ON CONFLICT(comic_id) DO UPDATE SET chapter_id=excluded.chapter_id,page_number=excluded.page_number,updated_at=excluded.updated_at,media_id=excluded.media_id
 RETURNING comic_id,chapter_id,page_number,updated_at,media_id`, time.Now().UTC().Format(jobsdomain.TimestampFormat), chapterID, comicID, value).Scan(&result.ComicID, &result.ChapterID, &result.PageNumber, &result.UpdatedAt, &result.MediaID)
	if errors.Is(err, sql.ErrNoRows) {
		err = librarydomain.ErrInvalidProgress
	}
	return result, err
}

// PlanWriter 将导入计划封装为提交动作，由任务引擎在完成事务内执行。
// 导入模块无需访问数据库连接或拼装 SQL。
func (service *sqliteRepository) PlanWriter(plan librarydomain.Plan) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error { return service.InsertTx(ctx, tx, plan) }
}

// InsertTx 只接受已发布并校验的内部导入计划，由任务提交事务调用。
func (service *sqliteRepository) InsertTx(ctx context.Context, tx *sql.Tx, plan librarydomain.Plan) error {
	if err := librarydomain.ValidateImportedProgress(plan); err != nil {
		return err
	}
	plan.ComicInfo = librarydomain.CleanComicInfo(plan.ComicInfo)
	if err := librarydomain.ValidateComicInfo(plan.ComicInfo); err != nil {
		return err
	}
	if err := enrichImport(ctx, tx, &plan.Comic); err != nil {
		return err
	}
	var categoryID any
	if plan.Comic.Category != nil {
		categoryID = plan.Comic.Category.ID
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO comics(id,title,created_at,author,description,category_id,category_name,original_title) VALUES(?,?,?,?,?,?,?,?)", plan.Comic.ID, plan.Comic.Title, plan.Comic.CreatedAt, plan.Comic.Author, plan.Comic.Description, categoryID, plan.Comic.CategoryName, librarydomain.CleanOriginalTitle(plan.Comic.OriginalTitle)); err != nil {
		return err
	}
	if plan.ComicInfo != nil {
		data, err := json.Marshal(plan.ComicInfo)
		if err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, "INSERT INTO comic_source_metadata VALUES(?,?)", plan.Comic.ID, string(data)); err != nil {
			return err
		}
	}

	for _, tag := range plan.Comic.Tags {
		if _, err := tx.ExecContext(ctx, "INSERT INTO comic_tags VALUES(?,?,?)", plan.Comic.ID, tag.ID, tag.Source); err != nil {
			return err
		}
	}
	for _, node := range plan.Catalogs {
		if _, err := tx.ExecContext(ctx, "INSERT INTO catalogs VALUES(?,?,?,?,?)", node.ID, plan.Comic.ID, node.ParentID, node.Title, node.SortOrder); err != nil {
			return err
		}
	}
	for _, chapter := range plan.Chapters {
		if _, err := tx.ExecContext(ctx, "INSERT INTO chapters(id,comic_id,catalog_id,title,global_order,sort_order) VALUES(?,?,?,?,?,?)", chapter.ID, plan.Comic.ID, chapter.CatalogID, chapter.Title, chapter.GlobalOrder, chapter.GlobalOrder); err != nil {
			return err
		}
	}
	for _, item := range plan.Media {
		var video any
		if item.Video != nil {
			data, err := json.Marshal(item.Video)
			if err != nil {
				return err
			}
			video = string(data)
		}
		if _, err := tx.ExecContext(ctx, "INSERT INTO media(id,chapter_id,page_number,file_name,relative_path,media_type,content_type,size,width,height,sha256,video_metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", item.ID, item.ChapterID, item.PageNumber, item.FileName, item.RelativePath, item.MediaType, item.ContentType, item.Size, item.Width, item.Height, item.SHA256, video); err != nil {
			return err
		}
	}
	if err := initializeImportStructure(ctx, tx, plan.Comic.ID); err != nil {
		return err
	}
	if progress := plan.Progress; progress != nil {
		if _, err := tx.ExecContext(ctx, "INSERT INTO reading_progress(comic_id,chapter_id,page_number,updated_at,media_id) VALUES(?,?,?,?,?)", progress.ComicID, progress.ChapterID, progress.PageNumber, progress.UpdatedAt, progress.MediaID); err != nil {
			return err
		}
	}
	return nil
}

func scanMedia(scanner interface{ Scan(...any) error }) (librarydomain.Media, error) {
	var item librarydomain.Media
	var video *string
	var lqSHA, taskID, taskStatus, itemStatus, errorCode *string
	var width, height *int
	var size *int64
	var valid *bool
	var origin, reference, originalState *string
	var registrationTask string
	err := scanner.Scan(&item.ID, &item.ChapterID, &item.PageNumber, &item.FileName, &item.RelativePath, &item.MediaType, &item.ContentType, &item.Size, &item.Width, &item.Height, &item.SHA256, &video, &lqSHA, &width, &height, &size, &valid, &taskID, &taskStatus, &itemStatus, &errorCode, &item.HQState, &item.StorageState, &origin, &reference, &originalState, &registrationTask, &item.MetadataOnly)
	if err == nil && video != nil {
		item.Video = &mediadomain.Video{}
		err = json.Unmarshal([]byte(*video), item.Video)
	}
	item.ContentURL = "/api/v1/media/" + item.ID + "/content"
	item.AutoContentURL = item.ContentURL + "?quality=auto"
	if err == nil && item.MediaType == "image" {
		var record *mediadomain.LQRecord
		if size != nil {
			record = &mediadomain.LQRecord{MediaID: item.ID, TaskID: registrationTask, SourceSHA256: *lqSHA, Width: *width, Height: *height, Size: *size, Valid: *valid}
			if origin != nil && *origin != "generated" {
				record.Provenance = &mediadomain.LQProvenance{Origin: *origin}
				if reference != nil {
					record.Provenance.ReferenceSHA256 = *reference
				}
				if originalState != nil {
					record.Provenance.OriginalState = *originalState
				}
			}
		}
		if !item.MetadataOnly {
			item.StorageState = mediadomain.OriginalStorageState(item.SHA256, item.StorageState, record)
		}
		if errorCode != nil {
			normalized := jobsdomain.WithDiagnostics(jobsdomain.Task{Status: "failed", ErrorCode: errorCode})
			errorCode = normalized.ErrorCode
		}
		info := mediadomain.LQState(item.ID, item.SHA256, record, taskID, taskStatus, itemStatus, errorCode)
		item.LQ = &info
	}
	return item, err
}
