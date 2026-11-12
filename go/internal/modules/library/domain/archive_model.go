package domain

// ArchiveRequest 是可移植归档附带的生成选项；导入方只读取元数据，不复用这些选项。
type ArchiveRequest struct {
	Format    string `json:"format"`
	PartBytes int64  `json:"partBytes"`
}

// ArchiveSource 描述归档内媒体位置，不包含主机文件系统路径。
type ArchiveSource struct {
	MediaID    string `json:"mediaId"`
	ChapterID  string `json:"chapterId"`
	PageNumber int    `json:"pageNumber"`
	FileName   string `json:"fileName"`
	MediaType  string `json:"mediaType"`
	Quality    string `json:"quality"`
	Path       string `json:"path"`
	SHA256     string `json:"sha256"`
	Size       int64  `json:"size"`
}

// ArchiveMetadata 是导入与导出共享的可移植资料结构，不依赖任何一侧的应用服务。
type ArchiveMetadata struct {
	Version   int             `json:"version"`
	Request   ArchiveRequest  `json:"request"`
	Comic     Comic           `json:"comic"`
	ComicInfo *ComicInfo      `json:"sourceComicInfo"`
	Catalogs  []Catalog       `json:"catalogs"`
	Chapters  []Chapter       `json:"chapters"`
	Sources   []ArchiveSource `json:"sources"`
	Progress  *Progress       `json:"progress,omitempty"`
}
