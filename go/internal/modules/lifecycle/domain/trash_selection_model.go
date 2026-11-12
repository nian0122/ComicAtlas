package domain

const MaxSelectedTargets = 10000
const MaxTrashMembers = 10000

type TargetFilter struct {
	Keyword   string `json:"keyword,omitempty"`
	ComicID   string `json:"comicId,omitempty"`
	ChapterID string `json:"chapterId,omitempty"`
	Reaction  string `json:"reaction,omitempty"`
	MediaType string `json:"mediaType,omitempty"`
}

type TargetSelection struct {
	Mode        string        `json:"mode"`
	IDs         []string      `json:"ids,omitempty"`
	Filter      *TargetFilter `json:"filter,omitempty"`
	ExcludedIDs []string      `json:"excludedIds,omitempty"`
}

type SelectedTarget struct {
	Target    Target
	Stamp     string
	Blocker   string
	FileCount int
}

// TrashMediaFact 只记录目标身份和登记内容，不包含回收其他对象时会整理的页码。
type TrashMediaFact struct {
	ID, ChapterID, FileName, Path, MediaType, ContentType, SHA256, HQState string
	Size                                                                   int64
	Width, Height                                                          *int
	VideoMetadata                                                          *string
}
