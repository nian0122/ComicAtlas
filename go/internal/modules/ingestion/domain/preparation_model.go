package domain

import librarydomain "comicatlas.local/backend/internal/modules/library/domain"

// Preparation 在移动来源前保存；Files 使用相对路径，业务 ID 在规范化完成后生成。
type Preparation struct {
	Version    int                            `json:"version"`
	TaskID     string                         `json:"taskId"`
	SourcePath string                         `json:"sourcePath"`
	Archive    bool                           `json:"archive"`
	Files      []PreparedFile                 `json:"files"`
	Comic      librarydomain.Comic            `json:"comic"`
	ComicInfo  *librarydomain.ComicInfo       `json:"comicInfo"`
	Document   *librarydomain.ArchiveMetadata `json:"document"`
	Layout     *Layout                        `json:"layout"`
}

type PreparedFile struct {
	Source     string `json:"source"`
	Normalized string `json:"normalized"`
	Size       int64  `json:"size"`
	Modified   int64  `json:"modified"`
	Identity   string `json:"identity"`
	Hash       string `json:"hash"`
}
