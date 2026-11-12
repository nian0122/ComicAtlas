package application

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"path"
	"strings"

	"archive/zip"
	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

const maxArchiveMetadataBytes = 16 << 20

func readArchiveMetadata(ctx context.Context, file *zip.File) (*librarydomain.ArchiveMetadata, error) {
	if file.UncompressedSize64 > maxArchiveMetadataBytes {
		return nil, ingestiondomainMetadataFailure(ingestiondomain.ErrLimit)
	}
	input, err := file.Open()
	if err != nil {
		return nil, archiveContentFailure(err)
	}
	data, readErr := io.ReadAll(io.LimitReader(contextReader{ctx: ctx, reader: input}, maxArchiveMetadataBytes+1))
	closeErr := input.Close()
	if err = errors.Join(readErr, closeErr); err != nil {
		return nil, archiveContentFailure(err)
	}
	if len(data) > maxArchiveMetadataBytes {
		return nil, ingestiondomainMetadataFailure(ingestiondomain.ErrLimit)
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var document librarydomain.ArchiveMetadata
	if err = decoder.Decode(&document); err != nil {
		return nil, ingestiondomainMetadataFailure(err)
	}
	if err = decoder.Decode(new(any)); err != io.EOF {
		return nil, ingestiondomainMetadataFailure(ingestiondomain.ErrMetadata)
	}
	if err = validateArchiveMetadata(document); err != nil {
		return nil, ingestiondomainMetadataFailure(err)
	}
	return &document, nil
}

func validateArchiveMetadata(document librarydomain.ArchiveMetadata) error {
	request := document.Request
	if document.Version != 1 || request.Format != "zip" && request.Format != "cbz" || request.PartBytes != 0 && (request.PartBytes < 64<<10 || request.PartBytes > 4<<30-1) || document.Comic.ID == "" || len(document.Comic.ID) > 128 || len(document.Catalogs)+len(document.Chapters) > librarydomain.MaxStructureNodes || len(document.Sources) < 1 || len(document.Sources) > MaxMedia {
		return ingestiondomain.ErrMetadata
	}
	if err := librarydomain.ValidateImportedMetadata(document.Comic); err != nil {
		return err
	}
	if err := librarydomain.ValidateComicInfo(document.ComicInfo); err != nil {
		return err
	}
	catalogs := make(map[string]bool, len(document.Catalogs))
	catalogParents := make(map[string]string, len(document.Catalogs))
	chapters := make(map[string]bool, len(document.Chapters))
	for _, catalog := range document.Catalogs {
		if catalog.ID == "" || len(catalog.ID) > 128 || catalogs[catalog.ID] || !librarydomain.ValidName(catalog.Title, 500) || catalog.SortOrder < 0 {
			return ingestiondomain.ErrMetadata
		}
		catalogs[catalog.ID] = true
	}
	for _, catalog := range document.Catalogs {
		if catalog.ParentID != nil && !catalogs[*catalog.ParentID] {
			return ingestiondomain.ErrMetadata
		}
		if catalog.ParentID != nil {
			catalogParents[catalog.ID] = *catalog.ParentID
		}
	}
	depths, visiting := map[string]int{}, map[string]bool{}
	var catalogDepth func(string) (int, error)
	catalogDepth = func(id string) (int, error) {
		if depth, ok := depths[id]; ok {
			return depth, nil
		}
		if visiting[id] {
			return 0, ingestiondomain.ErrMetadata
		}
		visiting[id] = true
		depth := 1
		if parent := catalogParents[id]; parent != "" {
			parentDepth, err := catalogDepth(parent)
			if err != nil {
				return 0, err
			}
			depth = parentDepth + 1
		}
		delete(visiting, id)
		if depth > librarydomain.MaxStructureDepth {
			return 0, ingestiondomain.ErrMetadata
		}
		depths[id] = depth
		return depth, nil
	}
	for id := range catalogs {
		if _, err := catalogDepth(id); err != nil {
			return err
		}
	}
	globalOrders := make(map[int]bool, len(document.Chapters))
	for _, chapter := range document.Chapters {
		if chapter.ID == "" || len(chapter.ID) > 128 || chapters[chapter.ID] || !librarydomain.ValidName(chapter.Title, 500) || chapter.GlobalOrder < 1 || globalOrders[chapter.GlobalOrder] || chapter.SortOrder < 0 || chapter.MediaVersion < 1 || chapter.CatalogID != nil && !catalogs[*chapter.CatalogID] {
			return ingestiondomain.ErrMetadata
		}
		chapters[chapter.ID], globalOrders[chapter.GlobalOrder] = true, true
	}
	paths, media := map[string]bool{}, map[string]bool{}
	chapterPages := map[string]map[int]bool{}
	var total int64
	for _, source := range document.Sources {
		if source.MediaID == "" || len(source.MediaID) > 128 || media[source.MediaID] || !chapters[source.ChapterID] || source.PageNumber < 1 || source.FileName == "" || len(source.FileName) > 255 || path.Base(source.FileName) != source.FileName || strings.ContainsAny(source.FileName, "\\:\x00") || source.MediaType != "image" && source.MediaType != "video" || source.Quality != "hq" && source.Quality != "lq" || source.Path == "" || path.Clean(source.Path) != source.Path || path.IsAbs(source.Path) || strings.ContainsAny(source.Path, "\\:\x00") || len(source.Path) > 2048 || source.SHA256 == "" || strings.ToLower(source.SHA256) != source.SHA256 || source.Size < 0 || source.Size > MaxFileBytes || paths[source.Path] {
			return ingestiondomain.ErrMetadata
		}
		if _, err := hex.DecodeString(source.SHA256); err != nil || len(source.SHA256) != 64 {
			return ingestiondomain.ErrMetadata
		}
		if chapterPages[source.ChapterID] == nil {
			chapterPages[source.ChapterID] = map[int]bool{}
		}
		if chapterPages[source.ChapterID][source.PageNumber] {
			return ingestiondomain.ErrMetadata
		}
		chapterPages[source.ChapterID][source.PageNumber] = true
		format, _, ok := ingestiondomain.MediaFormat(source.Path)
		if !ok || format != source.MediaType {
			return ingestiondomain.ErrMetadata
		}
		paths[source.Path], media[source.MediaID] = true, true
		total += source.Size
		if total > MaxTotalBytes {
			return ingestiondomain.ErrLimit
		}
	}
	if progress := document.Progress; progress != nil {
		found := false
		for _, source := range document.Sources {
			if source.MediaID == progress.MediaID && source.ChapterID == progress.ChapterID && source.PageNumber == progress.PageNumber {
				found = true
				break
			}
		}
		if !found || progress.ComicID != document.Comic.ID || len(progress.UpdatedAt) > 40 {
			return ingestiondomain.ErrMetadata
		}
	}
	return nil
}

func ingestiondomainMetadataFailure(err error) error {
	return jobsdomain.NewFailure(jobsdomain.FailureMetadata, err)
}

func restoreArchiveMetadata(task jobsdomain.Task, plan *librarydomain.Plan, document librarydomain.ArchiveMetadata, copied map[string]librarydomain.Media) error {
	mediaBySource := make(map[string]librarydomain.Media, len(document.Sources))
	for _, source := range document.Sources {
		media, ok := copied[source.Path]
		if !ok || media.Size != source.Size || media.SHA256 != source.SHA256 || media.MediaType != source.MediaType {
			return ingestiondomainMetadataFailure(ingestiondomain.ErrMetadata)
		}
		mediaBySource[source.Path] = media
	}
	catalogIDs := make(map[string]string, len(document.Catalogs))
	for _, catalog := range document.Catalogs {
		id, err := librarydomain.NewID("cat_")
		if err != nil {
			return err
		}
		catalogIDs[catalog.ID] = id
	}
	chapterIDs := make(map[string]string, len(document.Chapters))
	for _, chapter := range document.Chapters {
		id, err := librarydomain.NewID("ch_")
		if err != nil {
			return err
		}
		chapterIDs[chapter.ID] = id
	}
	mediaIDs := make(map[string]string, len(document.Sources))
	for _, source := range document.Sources {
		media, ok := mediaBySource[source.Path]
		if !ok {
			return ingestiondomainMetadataFailure(ingestiondomain.ErrMetadata)
		}
		mediaIDs[source.MediaID] = media.ID
	}
	catalogs := make([]librarydomain.Catalog, 0, len(document.Catalogs))
	for _, item := range document.Catalogs {
		item.ID = catalogIDs[item.ID]
		if item.ParentID != nil {
			id := catalogIDs[*item.ParentID]
			item.ParentID = &id
		}
		catalogs = append(catalogs, item)
	}
	chapters := make([]librarydomain.Chapter, 0, len(document.Chapters))
	for _, item := range document.Chapters {
		item.ID = chapterIDs[item.ID]
		if item.CatalogID != nil {
			id := catalogIDs[*item.CatalogID]
			item.CatalogID = &id
		}
		chapters = append(chapters, item)
	}
	media := make([]librarydomain.Media, 0, len(document.Sources))
	for _, source := range document.Sources {
		item := mediaBySource[source.Path]
		item.ChapterID = chapterIDs[source.ChapterID]
		item.PageNumber = source.PageNumber
		item.FileName = source.FileName
		item.RelativePath = "hq/" + task.ComicID + "/" + item.ChapterID + "/" + item.FileName
		media = append(media, item)
	}
	plan.Catalogs, plan.Chapters, plan.Media = catalogs, chapters, media
	plan.Comic.ID = task.ComicID
	plan.Comic.Title = document.Comic.Title
	if task.TitleExplicit {
		plan.Comic.Title = task.Title
	}
	plan.Comic.OriginalTitle = document.Comic.OriginalTitle
	plan.Comic.Author = document.Comic.Author
	plan.Comic.Description = document.Comic.Description
	plan.Comic.CategoryName = document.Comic.CategoryName
	plan.Comic.Tags = document.Comic.Tags
	if document.Progress != nil {
		progress := *document.Progress
		progress.ComicID = task.ComicID
		progress.ChapterID = chapterIDs[progress.ChapterID]
		progress.MediaID = mediaIDs[progress.MediaID]
		plan.Progress = &progress
	}
	if err := librarydomain.ValidateImportedProgress(*plan); err != nil {
		return ingestiondomainMetadataFailure(err)
	}
	return nil
}
