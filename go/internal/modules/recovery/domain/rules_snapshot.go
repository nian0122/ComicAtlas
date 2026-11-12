package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"strings"
	"time"
)

func validateSnapshot(comic string, value librarydomain.Snapshot) error {
	if value.Version != 1 || value.Detail.ID != comic || value.Detail.Version < 1 || value.Detail.StructureVersion < 1 || librarydomain.ValidateImportedMetadata(value.Detail.Comic) != nil || librarydomain.ValidateComicInfo(value.Detail.ComicInfo) != nil {
		return ErrMetadata
	}
	if choice := value.CoverSelection; choice != nil {
		valid := false
		for _, item := range value.Media {
			if item.ID == choice.MediaID && item.MediaType == "image" {
				valid = true
				break
			}
		}
		if choice.ComicID != comic || choice.Version < 1 || !valid {
			return ErrMetadata
		}
	}
	if len(value.Media)+len(value.SuppressedPaths) > librarydomain.MaxSnapshotMedia || len(value.Detail.Catalogs)+len(value.Detail.Chapters) > librarydomain.MaxStructureNodes {
		return ErrLimit
	}
	if len(value.Detail.CreatedAt) > 40 {
		return ErrMetadata
	}
	if _, err := time.Parse(time.RFC3339Nano, value.Detail.CreatedAt); err != nil {
		return ErrMetadata
	}
	if category := value.Detail.Category; category != nil && (!Key(category.ID) || category.Version < 1 || !librarydomain.ValidName(category.Name, 200)) {
		return ErrMetadata
	}
	tags := map[string]bool{}
	for _, tag := range value.Detail.Tags {
		if !Key(tag.ID) || tags[tag.ID] || tag.Version < 1 || !librarydomain.ValidName(tag.Name, 200) || tag.Source != "manual" && tag.Source != "comicinfo" && tag.Source != "auto" {
			return ErrMetadata
		}
		tags[tag.ID] = true
	}
	catalogs := map[string]*string{}
	for _, node := range value.Detail.Catalogs {
		if !Key(node.ID) || !librarydomain.ValidName(node.Title, 500) {
			return ErrMetadata
		}
		if _, exists := catalogs[node.ID]; exists {
			return ErrMetadata
		}
		catalogs[node.ID] = node.ParentID
	}
	for id := range catalogs {
		seen := map[string]bool{}
		for current := id; current != ""; {
			parent, found := catalogs[current]
			if !found || seen[current] || len(seen) >= librarydomain.MaxStructureDepth {
				return ErrMetadata
			}
			seen[current] = true
			current = ""
			if parent != nil {
				if !Key(*parent) {
					return ErrMetadata
				}
				current = *parent
			}
		}
	}
	chapters := map[string]bool{}
	orders := map[int]bool{}
	for _, chapter := range value.Detail.Chapters {
		if !Key(chapter.ID) || chapters[chapter.ID] || !librarydomain.ValidName(chapter.Title, 500) || chapter.GlobalOrder < 1 || orders[chapter.GlobalOrder] || chapter.MediaVersion < 1 {
			return ErrMetadata
		}
		if chapter.CatalogID != nil {
			if _, exists := catalogs[*chapter.CatalogID]; !exists {
				return ErrMetadata
			}
		}
		chapters[chapter.ID] = true
		orders[chapter.GlobalOrder] = true
	}
	ids := map[string]bool{}
	paths := map[string]bool{}
	pages := map[string]map[int]bool{}
	for _, media := range value.Media {
		if !Key(media.ID) || ids[media.ID] || !chapters[media.ChapterID] || media.PageNumber < 1 || !librarydomain.ValidName(media.FileName, 255) || strings.ContainsAny(media.FileName, "/\\:") || (!validHash(media.Hash) && (media.Hash != "" || librarydomain.OriginalAvailability(media) == "unknown")) || media.Size < 0 || media.Size > 20<<30 || !sourcePath(media.StoragePath, "hq/"+comic) {
			return ErrMetadata
		}
		if media.MediaType != "image" && media.MediaType != "video" || media.HQState != "available" && media.HQState != "released" || media.HQState == "released" && media.MediaType != "image" {
			return ErrMetadata
		}
		if media.ContentType == "" || len(media.ContentType) > 255 || media.Width != nil && *media.Width < 1 || media.Height != nil && *media.Height < 1 {
			return ErrMetadata
		}
		if video := media.Video; video != nil {
			if media.MediaType != "video" || video.Width < 1 || video.Height < 1 || len(video.Container) > 128 || len(video.VideoCodec) > 128 || len(video.AudioCodecs) > 32 || len(video.PixelFormat) > 128 || len(video.PlaybackHint) > 128 || video.DurationMS != nil && *video.DurationMS < 0 {
				return ErrMetadata
			}
			for _, codec := range video.AudioCodecs {
				if len(codec) > 128 {
					return ErrMetadata
				}
			}
		}
		if pages[media.ChapterID] == nil {
			pages[media.ChapterID] = map[int]bool{}
		}
		if pages[media.ChapterID][media.PageNumber] || paths[strings.ToLower(media.StoragePath)] {
			return ErrMetadata
		}
		ids[media.ID] = true
		pages[media.ChapterID][media.PageNumber] = true
		paths[strings.ToLower(media.StoragePath)] = true
		if lq := media.LQFile; lq != nil {
			if media.MediaType != "image" || lq.MediaID != media.ID || (lq.TaskID == "" && (lq.Provenance == nil || lq.Provenance.Origin != "imported")) || lq.TaskID != "" && !Key(lq.TaskID) || mediadomain.ValidateLQProvenance(lq.Provenance, lq.TaskID) != nil || !sourcePath(lq.StoragePath, "lq") || !validHash(lq.Hash) || (!validHash(lq.SourceHash) && (lq.SourceHash != "" || !lq.MatchesSource(""))) || lq.Size < 0 || lq.Size > 20<<30 || lq.Width < 1 || lq.Height < 1 {
				return ErrMetadata
			}
		}
	}
	for _, name := range value.SuppressedPaths {
		if !sourcePath(name, "hq/"+comic) || paths[strings.ToLower(name)] {
			return ErrMetadata
		}
		paths[strings.ToLower(name)] = true
	}
	return nil
}
