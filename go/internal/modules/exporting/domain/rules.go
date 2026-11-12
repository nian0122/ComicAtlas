package domain

import (
	"encoding/hex"
	"errors"
	"fmt"
	"path"
	"strings"
)

const MaxMedia = 10000
const MaxSnapshotBytes = 16 << 20
const MaxNodes = 20000

var ErrValidation = errors.New("导出参数或快照无效")
var ErrNotFound = errors.New("导出目标不存在")
var ErrConflict = errors.New("导出与媒体任务冲突")
var ErrSource = errors.New("导出来源不可用或已变化")
var ErrStorage = errors.New("导出存储操作失败")
var ErrManifest = errors.New("导出发布清单无效")

func Normalize(request Request) (Request, error) {
	if request.Format == "" {
		request.Format = "zip"
	}
	if request.Format != "zip" && request.Format != "cbz" {
		return request, ErrValidation
	}
	if request.PartBytes != 0 && (request.PartBytes < 64<<10 || request.PartBytes > 4<<30-1) {
		return request, ErrValidation
	}
	return request, nil
}
func TaskID(id string) bool {
	if len(id) != 37 || !strings.HasPrefix(id, "task_") {
		return false
	}
	return digest(id[5:] + id[5:])
}
func digest(value string) bool {
	if len(value) != 64 || strings.ToLower(value) != value {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}
func sourcePath(value, prefix string) bool {
	return strings.HasPrefix(value, prefix+"/") && path.Clean(value) == value && !strings.ContainsAny(value, "\\:") && !strings.HasPrefix(value, "/") && len(value) <= 2048
}
func SelectSource(candidate Candidate) (Source, error) {
	value := candidate.Source
	value.Quality = "hq"
	if candidate.HQState == "releasing" {
		return value, ErrConflict
	}
	if candidate.HQState != "available" || value.SHA256 == "" {
		if value.MediaType != "image" || (candidate.HQState != "released" && candidate.HQState != "available") || !candidate.LQCurrent {
			return value, ErrSource
		}
		value.Path, value.SHA256, value.Size, value.Quality = candidate.LQPath, candidate.LQSHA, candidate.LQSize, "lq"
	}
	if !sourcePath(value.Path, value.Quality) || !digest(value.SHA256) || value.Size < 0 || value.Size > 20<<30 {
		return value, ErrSource
	}
	return value, nil
}
func ValidateSnapshot(snapshot Snapshot) error {
	request, err := Normalize(snapshot.Request)
	if err != nil || request != snapshot.Request || snapshot.Version != 1 || snapshot.Comic.ID == "" || len(snapshot.Sources) > MaxMedia || len(snapshot.Catalogs)+len(snapshot.Chapters) > MaxNodes {
		return ErrValidation
	}
	var total int64
	seen := map[string]bool{}
	media := map[string]Source{}
	for _, source := range snapshot.Sources {
		if source.MediaID == "" || seen[source.MediaID] || source.ChapterID == "" || source.PageNumber < 1 || (source.MediaType != "image" && source.MediaType != "video") || (source.Quality != "hq" && source.Quality != "lq") || !sourcePath(source.Path, source.Quality) || !digest(source.SHA256) || source.Size < 0 || source.Size > 20<<30 {
			return ErrValidation
		}
		seen[source.MediaID] = true
		media[source.MediaID] = source
		total += source.Size
		if total > 100<<30 {
			return ErrValidation
		}
	}
	if progress := snapshot.Progress; progress != nil {
		source, ok := media[progress.MediaID]
		if !ok || progress.ComicID != snapshot.Comic.ID || progress.ChapterID != source.ChapterID || progress.PageNumber != source.PageNumber || len(progress.UpdatedAt) > 40 {
			return ErrValidation
		}
	}
	return nil
}
func ValidateManifest(manifest Manifest, id, hash string, snapshot Snapshot) error {
	if manifest.Version != 2 || manifest.TaskID != id || !TaskID(id) || manifest.SnapshotSHA != hash || len(manifest.Artifacts) < 1 || len(manifest.Artifacts) > 128 {
		return ErrManifest
	}
	request := snapshot.Request
	base := strings.TrimSuffix(ArchiveName(snapshot), "."+request.Format)
	if request.PartBytes == 0 && len(manifest.Artifacts) != 1 {
		return ErrManifest
	}
	var total int64
	for index, artifact := range manifest.Artifacts {
		name := fmt.Sprintf("%s.z%02d", base, index+1)
		last := index == len(manifest.Artifacts)-1
		if last {
			name = base + "." + request.Format
		}
		if artifact.FileName != name || artifact.Size <= 0 || !digest(artifact.SHA256) || artifact.LastSegment != last || artifact.Path != "" || request.PartBytes > 0 && artifact.Size > request.PartBytes {
			return ErrManifest
		}
		total += artifact.Size
		if total < 0 || total > 100<<30+16<<20 {
			return ErrManifest
		}
	}
	return nil
}
