package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"comicatlas.local/backend/internal/platform/natural"
	"crypto/sha256"
	"encoding/hex"
	"path"
	"sort"
	"strings"
)

var formats = map[string][2]string{
	".jpg": {"image", "image/jpeg"}, ".jpeg": {"image", "image/jpeg"}, ".png": {"image", "image/png"}, ".gif": {"image", "image/gif"}, ".webp": {"image", "image/webp"},
	".mp4": {"video", "video/mp4"}, ".webm": {"video", "video/webm"}, ".mov": {"video", "video/quicktime"}, ".mkv": {"video", "video/x-matroska"}, ".avi": {"video", "video/x-msvideo"},
}

func MediaFormat(name string) (string, string, bool) {
	value, ok := formats[strings.ToLower(path.Ext(name))]
	return value[0], value[1], ok
}
func stable(prefix, taskID, name string) string {
	sum := sha256.Sum256([]byte(taskID + "\x00" + name))
	return prefix + hex.EncodeToString(sum[:16])
}
func NormalizeLayout(identity string, comic librarydomain.Comic, files []string) (Layout, error) {
	if len(files) < 1 {
		return Layout{}, ErrNoMedia
	}
	if len(files) > 10000 {
		return Layout{}, ErrLimit
	}
	files = append([]string(nil), files...)
	sort.Slice(files, func(i, j int) bool {
		comparison := natural.Compare(files[i], files[j])
		if comparison == 0 {
			return files[i] < files[j]
		}
		return comparison < 0
	})
	plan := librarydomain.Plan{Comic: librarydomain.Comic{ID: comic.ID, Title: comic.Title, CreatedAt: comic.CreatedAt}, Catalogs: []librarydomain.Catalog{}, Chapters: []librarydomain.Chapter{}, Media: []librarydomain.Media{}}
	byDirectory := map[string][]string{}
	allDirectories := map[string]bool{}
	hasDescendants := map[string]bool{}
	for _, file := range files {
		if path.Clean(file) != file || strings.HasPrefix(file, "/") || file == ".." || strings.HasPrefix(file, "../") || strings.ContainsAny(file, "\\:\x00") {
			return Layout{}, ErrUnsafeSource
		}
		if strings.Count(file, "/") > 64 || len(file) > 2048 {
			return Layout{}, ErrLimit
		}
		if _, _, ok := MediaFormat(file); !ok {
			return Layout{}, ErrUnsafeSource
		}
		parent := path.Dir(file)
		byDirectory[parent] = append(byDirectory[parent], file)
		for current := parent; current != "."; current = path.Dir(current) {
			if !allDirectories[current] && len(allDirectories) >= librarydomain.MaxStructureNodes {
				return Layout{}, ErrLimit
			}
			allDirectories[current] = true
			hasDescendants[path.Dir(current)] = true
		}
	}
	names := []string{}
	for name := range allDirectories {
		names = append(names, name)
	}
	sort.Slice(names, func(i, j int) bool {
		comparison := natural.Compare(names[i], names[j])
		if comparison == 0 {
			return names[i] < names[j]
		}
		return comparison < 0
	})
	catalogIDs := map[string]string{}
	for _, name := range names {
		if hasDescendants[name] || len(byDirectory[name]) == 0 {
			catalogIDs[name] = stable("catalog_", identity, name)
		}
	}
	for _, name := range names {
		if id, ok := catalogIDs[name]; ok {
			var parent *string
			if parentID, ok := catalogIDs[path.Dir(name)]; ok {
				parent = &parentID
			}
			plan.Catalogs = append(plan.Catalogs, librarydomain.Catalog{ID: id, ParentID: parent, Title: path.Base(name), SortOrder: len(plan.Catalogs) + 1})
		}
	}
	chapterDirectories := []string{}
	if len(byDirectory["."]) > 0 {
		chapterDirectories = append(chapterDirectories, ".")
	}
	for _, name := range names {
		if len(byDirectory[name]) > 0 {
			chapterDirectories = append(chapterDirectories, name)
		}
	}
	sources := map[string]string{}
	for _, name := range chapterDirectories {
		chapter := librarydomain.Chapter{ID: stable("chapter_", identity, name), Title: path.Base(name), GlobalOrder: len(plan.Chapters) + 1}
		if name == "." {
			chapter.Title = "正文"
			if len(chapterDirectories) > 1 {
				chapter.Title = "本书散页"
			}
		} else if id, ok := catalogIDs[name]; ok {
			chapter.Title = "本目录散页"
			chapter.CatalogID = &id
		} else if id, ok := catalogIDs[path.Dir(name)]; ok {
			chapter.CatalogID = &id
		}
		if len(plan.Catalogs)+len(plan.Chapters) >= librarydomain.MaxStructureNodes {
			return Layout{}, ErrLimit
		}
		plan.Chapters = append(plan.Chapters, chapter)
		for index, file := range byDirectory[name] {
			item := librarydomain.Media{ID: stable("media_", identity, file), ChapterID: chapter.ID, PageNumber: index + 1, FileName: path.Base(file)}
			item.MediaType, item.ContentType, _ = MediaFormat(file)
			item.RelativePath = "hq/" + comic.ID + "/" + chapter.ID + "/" + item.FileName
			sources[item.ID] = file
			plan.Media = append(plan.Media, item)
		}
	}
	return Layout{Plan: plan, Sources: sources}, nil
}
