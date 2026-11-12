package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"path"
	"reflect"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

// DiscoveredImageID 由漫画、章节、路径和图片内容摘要派生登记 ID。图片确认后会计算
// 完整摘要，内容寻址避免同一路径被不同内容复用身份。
func DiscoveredImageID(comic, chapter, name, hash string) string {
	digest := sha256.Sum256([]byte("metadata-discovery-v1\x00" + comic + "\x00" + chapter + "\x00" + name + "\x00" + hash))
	return "media_" + hex.EncodeToString(digest[:16])
}

// DiscoveredVideoID 由漫画、章节和路径派生视频登记 ID。视频核对不读取整段内容，
// 因此登记身份不能依赖内容摘要。
func DiscoveredVideoID(comic, chapter, name string) string {
	digest := sha256.Sum256([]byte("metadata-discovery-v2\x00" + comic + "\x00" + chapter + "\x00" + name))
	return "media_" + hex.EncodeToString(digest[:16])
}

// discoveredIdentityMatches 校验发现项身份。图片确认后按内容寻址；视频按路径派生
// ID 且摘要必须未知。
func discoveredIdentityMatches(comic, chapter string, item MediaObservation) bool {
	if item.MediaID == nil || item.Inspection == nil {
		return false
	}
	if item.Inspection.MediaType == "video" {
		return item.SHA256 == "" && *item.MediaID == DiscoveredVideoID(comic, chapter, item.StoragePath)
	}
	return len(item.SHA256) == 64 && *item.MediaID == DiscoveredImageID(comic, chapter, item.StoragePath, item.SHA256)
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

func ObservedMediaStatus(item librarydomain.StoredMedia, info mediadomain.Inspection, size int64, hash string) string {
	if item.Hash != hash || item.Size != size || item.Width == nil || item.Height == nil || *item.Width != info.Width || *item.Height != info.Height || item.ContentType != info.ContentType || !reflect.DeepEqual(item.Video, info.Video) {
		return "changed"
	}
	return "present"
}

// observationHashValid 允许视频在内容变化后清空已失效的摘要；其余情况必须给出
// 完整 SHA-256，不能把未知摘要伪装成内容校验值。
func observationHashValid(status, hash string, media librarydomain.StoredMedia) bool {
	if len(hash) == 64 {
		return true
	}
	if hash != "" || media.MediaType != "video" {
		return false
	}
	return status == "changed" || media.Hash == ""
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
			if !discoveredIdentityMatches(baseline.Detail.ID, chapter, item) || names[chapter+"\x00"+strings.ToLower(item.FileName)] || counts[chapter] >= mediadomain.MaxUploadFiles {
				return next, ErrValidation
			}
			info := item.Inspection
			if mediadomain.ValidateInspection(*info, item.FileName) != nil {
				return next, ErrValidation
			}
			pages[chapter]++
			counts[chapter]++
			names[chapter+"\x00"+strings.ToLower(item.FileName)] = true
			next.Media = append(next.Media, librarydomain.StoredMedia{Media: librarydomain.Media{ID: id, ChapterID: chapter, PageNumber: pages[chapter], FileName: item.FileName, MediaType: info.MediaType, ContentType: info.ContentType, Size: item.Size, Width: &info.Width, Height: &info.Height, Video: info.Video, HQState: "available", StorageState: "present"}, StoragePath: item.StoragePath, Hash: item.SHA256})
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
			if item.Inspection == nil || item.Size < 1 || !observationHashValid(item.Status, item.SHA256, *media) {
				return next, ErrValidation
			}
			info := item.Inspection
			if mediadomain.ValidateInspection(*info, item.FileName) != nil {
				return next, ErrValidation
			}
			if info.MediaType != media.MediaType {
				return next, ErrValidation
			}
			media.Size, media.Hash, media.SHA256 = item.Size, item.SHA256, item.SHA256
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
			copy.Valid = item.LQValid != nil && *item.LQValid && copy.MatchesSource(media.Hash)
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
	_, _, err := librarydomain.SnapshotData(next)
	return next, err
}
func MetadataSidecarData(taskID string, run MetadataRun) ([]byte, string, error) {
	if run.Next == nil {
		return nil, "", ErrValidation
	}
	_, baselineHash, err := librarydomain.SnapshotData(run.Baseline)
	if err != nil {
		return nil, "", err
	}
	data, err := json.Marshal(MetadataSidecar{Version: 1, TaskID: taskID, ObservedAt: run.ObservedAt, BaselineSHA256: baselineHash, Snapshot: *run.Next, Observations: run.Observations})
	if err != nil {
		return nil, "", err
	}
	if len(data) > 2*librarydomain.MaxSnapshotBytes {
		return nil, "", ErrScanLimit
	}
	digest := sha256.Sum256(data)
	return data, hex.EncodeToString(digest[:]), nil
}

func ValidateMetadataRun(run MetadataRun) error {
	if _, _, err := librarydomain.SnapshotData(run.Baseline); err != nil {
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
	_, hash, err := librarydomain.SnapshotData(expected)
	if err != nil {
		return err
	}
	_, stored, err := librarydomain.SnapshotData(*run.Next)
	if err != nil {
		return err
	}
	if hash != stored {
		return ErrValidation
	}
	return nil
}
