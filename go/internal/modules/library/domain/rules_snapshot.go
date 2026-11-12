package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"reflect"
)

// OriginalAvailability 保留缺失、释放与无来源关联的区别；未知摘要必须有明确快照证据。
func OriginalAvailability(media StoredMedia) string {
	if media.Hash != "" {
		return media.HQState
	}
	if media.MediaType != "image" || media.LQFile == nil || media.LQFile.Provenance == nil || media.LQFile.Provenance.OriginalState == "" {
		return "unknown"
	}
	state := media.LQFile.Provenance.OriginalState
	if (state == "released") != (media.HQState == "released") {
		return "unknown"
	}
	if media.HQState != "available" && media.HQState != "released" {
		return "unknown"
	}
	return state
}

func StorageMediaChanged(before, after StoredMedia) bool {
	return before.Hash != after.Hash || before.Size != after.Size || before.ContentType != after.ContentType || !reflect.DeepEqual(before.Width, after.Width) || !reflect.DeepEqual(before.Height, after.Height) || !reflect.DeepEqual(before.Video, after.Video)
}

func StorageChanges(before, after Snapshot) (StorageDelta, error) {
	delta := StorageDelta{}
	if before.Detail.ID != after.Detail.ID || len(before.Media) > len(after.Media) || len(before.Detail.Chapters) != len(after.Detail.Chapters) {
		return delta, ErrValidation
	}
	old := map[string]StoredMedia{}
	seen := map[string]bool{}
	for _, item := range before.Media {
		old[item.ID] = item
	}
	matched := 0
	for _, item := range after.Media {
		if seen[item.ID] {
			return delta, ErrValidation
		}
		seen[item.ID] = true
		previous, found := old[item.ID]
		if !found {
			delta.Added = append(delta.Added, item)
			continue
		}
		matched++
		if previous.StoragePath != item.StoragePath || previous.ChapterID != item.ChapterID || previous.PageNumber != item.PageNumber {
			return delta, ErrValidation
		}
		lqChanged := previous.LQFile != nil && item.LQFile != nil && previous.LQFile.Valid != item.LQFile.Valid
		if StorageMediaChanged(previous, item) || lqChanged {
			delta.Changed = append(delta.Changed, item)
		}
	}
	if matched != len(before.Media) {
		return delta, ErrValidation
	}
	for i, item := range before.Detail.Chapters {
		next := after.Detail.Chapters[i]
		if item.ID != next.ID {
			return delta, ErrValidation
		}
		if item.MediaVersion != next.MediaVersion {
			delta.Chapters = append(delta.Chapters, next)
		}
	}
	return delta, nil
}

func SnapshotData(value Snapshot) ([]byte, string, error) {
	if value.Version != 1 || len(value.Media)+len(value.SuppressedPaths) > MaxSnapshotMedia || len(value.Detail.Catalogs)+len(value.Detail.Chapters) > MaxStructureNodes {
		return nil, "", ErrValidation
	}
	data, err := json.Marshal(value)
	if err != nil {
		return nil, "", err
	}
	if len(data) > MaxSnapshotBytes {
		return nil, "", ErrValidation
	}
	digest := sha256.Sum256(data)
	return data, hex.EncodeToString(digest[:]), nil
}
