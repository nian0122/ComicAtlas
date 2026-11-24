package domain

import (
	"bytes"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/stableid"
	"encoding/json"
	"path"
	"reflect"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

// DiscoveredMediaID 由漫画、章节和路径派生媒体 ID。
func DiscoveredMediaID(comic, chapter, name string) string {
	return "media_" + stableid.Hex("metadata-discovery\x00"+comic+"\x00"+chapter+"\x00"+name)
}

func DiscoveryNameAllowed(name string) bool {
	if name == "" || len(name) > 255 || !utf8.ValidString(name) || strings.ContainsAny(name, "/\\:") {
		return false
	}
	for _, r := range name {
		if unicode.IsControl(r) {
			return false
		}
	}
	return true
}

func MetadataDirectoryChapters(snapshot librarydomain.Snapshot) map[string]*string {
	result := map[string]*string{}
	assign := func(name, id string) {
		previous, found := result[name]
		if found && (previous == nil || *previous != id) {
			result[name] = nil
			return
		}
		copy := id
		result[name] = &copy
	}
	for _, chapter := range snapshot.Detail.Chapters {
		assign("hq/"+snapshot.Detail.ID+"/"+chapter.ID, chapter.ID)
	}
	for _, media := range snapshot.Media {
		assign(path.Dir(media.StoragePath), media.ChapterID)
	}
	return result
}
func MetadataNames(snapshot librarydomain.Snapshot) map[string]bool {
	result := map[string]bool{}
	for _, media := range snapshot.Media {
		result[media.ChapterID+"\x00"+strings.ToLower(media.FileName)] = true
	}
	return result
}

func ObservedMediaStatus(item librarydomain.StoredMedia, info mediadomain.Inspection, size, modified int64) string {
	if item.Modified != 0 && item.Modified != modified || item.Size != size || item.Width != nil && *item.Width != info.Width || item.Height != nil && *item.Height != info.Height || item.ContentType != info.ContentType || item.Video != nil && !reflect.DeepEqual(item.Video, info.Video) {
		return "changed"
	}
	return "present"
}

func ApplyObservations(baseline librarydomain.Snapshot, items []MediaObservation) (librarydomain.Snapshot, error) {
	if len(items) > librarydomain.MaxSnapshotMedia*2 {
		return baseline, ErrScanLimit
	}
	next := baseline
	next.Media = append([]librarydomain.StoredMedia{}, baseline.Media...)
	next.Detail.Chapters = append([]librarydomain.Chapter{}, baseline.Detail.Chapters...)
	positions := map[string]int{}
	for i, item := range next.Media {
		positions[item.ID] = i
	}
	seen := map[string]bool{}
	changedChapters := map[string]bool{}
	chapterOrder := map[string]int{}
	pages := map[string]int{}
	counts := map[string]int{}
	names := map[string]bool{}
	for _, chapter := range next.Detail.Chapters {
		chapterOrder[chapter.ID] = chapter.GlobalOrder
	}
	for _, media := range next.Media {
		pages[media.ChapterID] = max(pages[media.ChapterID], media.PageNumber)
		counts[media.ChapterID]++
		names[media.ChapterID+"\x00"+strings.ToLower(media.FileName)] = true
	}
	for i, item := range items {
		if item.Position != i+1 {
			return next, ErrValidation
		}
		if item.MediaID == nil {
			if item.Status != "discovered" && item.Status != "ignored" {
				return next, ErrValidation
			}
			continue
		}
		id := *item.MediaID
		index, found := positions[id]
		if seen[id] {
			return next, ErrValidation
		}
		if !found && item.Status == "discovered" {
			if item.ChapterID == nil || chapterOrder[*item.ChapterID] == 0 || item.Inspection == nil || item.Size < 1 || !DiscoveryNameAllowed(item.FileName) {
				return next, ErrValidation
			}
			chapter := *item.ChapterID
			if id != DiscoveredMediaID(baseline.Detail.ID, chapter, item.StoragePath) || names[chapter+"\x00"+strings.ToLower(item.FileName)] || counts[chapter] >= mediadomain.MaxUploadFiles {
				return next, ErrValidation
			}
			info := item.Inspection
			if mediadomain.ValidateInspection(*info, item.FileName) != nil {
				return next, ErrValidation
			}
			pages[chapter]++
			counts[chapter]++
			names[chapter+"\x00"+strings.ToLower(item.FileName)] = true
			next.Media = append(next.Media, librarydomain.StoredMedia{Media: librarydomain.Media{ID: id, ChapterID: chapter, PageNumber: pages[chapter], FileName: item.FileName, MediaType: info.MediaType, ContentType: info.ContentType, Size: item.Size, Width: &info.Width, Height: &info.Height, Video: info.Video, HQState: "available", StorageState: "present"}, StoragePath: item.StoragePath, Modified: item.Modified})
			changedChapters[chapter] = true
			continue
		}
		if !found {
			return next, ErrValidation
		}
		seen[id] = true
		media := &next.Media[index]
		if item.StoragePath != media.StoragePath || item.ChapterID == nil || *item.ChapterID != media.ChapterID {
			return next, ErrValidation
		}
		switch item.Status {
		case "present", "changed":
			if item.Inspection == nil || item.Size < 1 {
				return next, ErrValidation
			}
			info := item.Inspection
			if mediadomain.ValidateInspection(*info, item.FileName) != nil {
				return next, ErrValidation
			}
			if info.MediaType != media.MediaType {
				return next, ErrValidation
			}
			media.Size, media.Modified = item.Size, item.Modified
			media.Width, media.Height = &info.Width, &info.Height
			media.ContentType, media.Video = info.ContentType, info.Video
			if item.Status == "changed" {
				changedChapters[media.ChapterID] = true
			}
		case "missing", "released", "invalid":
		default:
			return next, ErrValidation
		}
		media.StorageState = item.Status
		if media.LQFile != nil {
			copy := *media.LQFile
			copy.Valid = item.LQValid != nil && *item.LQValid
			media.LQFile = &copy
		}
	}
	if len(seen) != len(baseline.Media) {
		return next, ErrValidation
	}
	for i := range next.Detail.Chapters {
		if changedChapters[next.Detail.Chapters[i].ID] {
			next.Detail.Chapters[i].MediaVersion++
		}
	}
	sort.Slice(next.Media, func(i, j int) bool {
		a, b := next.Media[i], next.Media[j]
		if chapterOrder[a.ChapterID] != chapterOrder[b.ChapterID] {
			return chapterOrder[a.ChapterID] < chapterOrder[b.ChapterID]
		}
		if a.PageNumber != b.PageNumber {
			return a.PageNumber < b.PageNumber
		}
		return a.ID < b.ID
	})
	_, err := librarydomain.SnapshotData(next)
	return next, err
}
func MetadataSidecarData(taskID string, run MetadataRun) ([]byte, error) {
	if run.Next == nil {
		return nil, ErrValidation
	}
	if _, err := librarydomain.SnapshotData(run.Baseline); err != nil {
		return nil, err
	}
	data, err := json.Marshal(MetadataSidecar{Version: 1, TaskID: taskID, ObservedAt: run.ObservedAt, Snapshot: *run.Next, Observations: run.Observations})
	if err != nil {
		return nil, err
	}
	if len(data) > 2*librarydomain.MaxSnapshotBytes {
		return nil, ErrScanLimit
	}
	return data, nil
}

func ValidateMetadataRun(run MetadataRun) error {
	if _, err := librarydomain.SnapshotData(run.Baseline); err != nil {
		return err
	}
	if run.Next == nil {
		if run.Applied {
			return ErrValidation
		}
		return nil
	}
	if _, err := time.Parse(time.RFC3339Nano, run.ObservedAt); err != nil || !strings.HasSuffix(run.ObservedAt, "Z") {
		return ErrValidation
	}
	expected, err := ApplyObservations(run.Baseline, run.Observations)
	if err != nil {
		return err
	}
	expectedData, err := librarydomain.SnapshotData(expected)
	if err != nil {
		return err
	}
	storedData, err := librarydomain.SnapshotData(*run.Next)
	if err != nil {
		return err
	}
	if !bytes.Equal(expectedData, storedData) {
		return ErrValidation
	}
	return nil
}
