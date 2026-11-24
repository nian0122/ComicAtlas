package domain

import librarydomain "comicatlas.local/backend/internal/modules/library/domain"

// Preparation 在搬移来源前保存；Layout 与 Target 一次固定，恢复时不再扫描或重算。
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
	Source   string `json:"source"`
	Target   string `json:"target"`
	Size     int64  `json:"size"`
	Modified int64  `json:"modified"`
	Identity string `json:"identity"`
}
