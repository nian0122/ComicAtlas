package domain

import (
	"bytes"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"encoding/hex"
	"encoding/json"
	"io"
	"path"
)

// 检查点使用持久模型；公开阅读模型会隐藏路径和摘要，不能直接用作恢复数据。
func (layout Layout) MarshalJSON() ([]byte, error) {
	plan := layout.Plan
	stored := Manifest{Version: 2, Comic: plan.Comic, ComicInfo: plan.ComicInfo, Catalogs: plan.Catalogs, Chapters: plan.Chapters, Progress: plan.Progress, Media: []StoredMedia{}}
	for _, item := range plan.Media {
		stored.Media = append(stored.Media, StoredMedia{Media: item, StoragePath: item.RelativePath, Hash: item.SHA256})
	}
	return json.Marshal(struct {
		Plan    Manifest          `json:"plan"`
		Sources map[string]string `json:"sources"`
	}{stored, layout.Sources})
}

func (layout *Layout) UnmarshalJSON(data []byte) error {
	var value struct {
		Plan    Manifest          `json:"plan"`
		Sources map[string]string `json:"sources"`
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&value) != nil || decoder.Decode(new(any)) != io.EOF || value.Plan.Version != 2 || value.Plan.Comic.ID == "" || len(value.Plan.Media) == 0 || len(value.Plan.Media) > 10000 || len(value.Sources) != len(value.Plan.Media) {
		return ErrManifest
	}
	stored := value.Plan
	plan := librarydomain.Plan{Comic: stored.Comic, ComicInfo: stored.ComicInfo, Catalogs: stored.Catalogs, Chapters: stored.Chapters, Progress: stored.Progress, Media: []librarydomain.Media{}}
	seen := map[string]bool{}
	for _, item := range stored.Media {
		if item.ID == "" || seen[item.ID] || value.Sources[item.ID] == "" || item.FileName == "" || path.Base(item.FileName) != item.FileName || item.StoragePath != "hq/"+stored.Comic.ID+"/"+item.ChapterID+"/"+item.FileName {
			return ErrManifest
		}
		if item.Hash != "" {
			decoded, err := hex.DecodeString(item.Hash)
			if err != nil || len(decoded) != 32 {
				return ErrManifest
			}
		}
		seen[item.ID] = true
		item.Media.RelativePath, item.Media.SHA256 = item.StoragePath, item.Hash
		plan.Media = append(plan.Media, item.Media)
	}
	layout.Plan, layout.Sources = plan, value.Sources
	return nil
}
