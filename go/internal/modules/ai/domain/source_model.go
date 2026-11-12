package domain

import mediadomain "comicatlas.local/backend/internal/modules/media/domain"

// SourceImage 是本机准备阶段的冻结来源，不属于发给 AI 的协议。
type SourceImage struct {
	MediaID string
	Ordinal int
	Path    string `json:"-"`
	SHA256  string `json:"-"`
}

type SourceChoice struct {
	MediaID, HQState, HQPath, HQSHA256 string
	Ordinal                            int
	LQPath, LQSHA256, LQSourceSHA256   string
	LQValid                            bool
	LQTaskID                           string
	LQProvenance                       *mediadomain.LQProvenance
}

type Snapshot struct {
	ComicID                           string
	MetadataVersion, StructureVersion int
	Images                            []SourceImage
	Categories, Tags                  []Term
}
