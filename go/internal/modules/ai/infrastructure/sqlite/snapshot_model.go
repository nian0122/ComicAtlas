package sqlite

import aidomain "comicatlas.local/backend/internal/modules/ai/domain"

// 独立的本地持久化格式，避免让来源路径进入外发和公开请求模型。
type storedSnapshot struct {
	ComicID          string          `json:"comicId"`
	MetadataVersion  int             `json:"metadataVersion"`
	StructureVersion int             `json:"structureVersion"`
	Categories       []aidomain.Term `json:"categories"`
	Tags             []aidomain.Term `json:"tags"`
	Images           []storedImage   `json:"images"`
}

type storedImage struct {
	MediaID string `json:"mediaId"`
	Ordinal int    `json:"ordinal"`
	Path    string `json:"path"`
	SHA256  string `json:"sha256"`
}
