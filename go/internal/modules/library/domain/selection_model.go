package domain

import mediadomain "comicatlas.local/backend/internal/modules/media/domain"

const MaxSelectedComics = 10000

type ComicFilter struct {
	Reaction   string `json:"reaction,omitempty"`
	Keyword    string `json:"keyword,omitempty"`
	CategoryID string `json:"categoryId,omitempty"`
	TagID      string `json:"tagId,omitempty"`
	ReadStatus string `json:"readStatus,omitempty"`
}
type ComicSelection struct {
	Mode        string       `json:"mode"`
	IDs         []string     `json:"ids,omitempty"`
	Filter      *ComicFilter `json:"filter,omitempty"`
	ExcludedIDs []string     `json:"excludedIds,omitempty"`
}
type SelectedComic struct {
	HQ            *mediadomain.HQReleasePreview `json:"-"`
	ScopeRevision int64                         `json:"-"`
	Images        int                           `json:"-"`
	Videos        int                           `json:"-"`
	ID            string                        `json:"id"`
	Title         string                        `json:"title"`
	Version       int                           `json:"version"`
}
type MetadataPatch struct {
	Title         *string  `json:"title,omitempty"`
	Author        *string  `json:"author,omitempty"`
	Description   *string  `json:"description,omitempty"`
	CategoryID    *string  `json:"categoryId,omitempty"`
	ClearCategory bool     `json:"clearCategory,omitempty"`
	AddTagIDs     []string `json:"addTagIds,omitempty"`
	RemoveTagIDs  []string `json:"removeTagIds,omitempty"`
}
