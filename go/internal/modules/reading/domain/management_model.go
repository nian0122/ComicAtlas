package domain

type ManagementQuery struct {
	TargetType     string
	Value          string
	MediaType      string
	IncludeTrashed bool
	Oldest         bool
	Page, PageSize int
}

type ReactionScope struct{ Target, Comic, Chapter string }

type ManagedReaction struct {
	Reaction
	ComicID        string  `json:"comicId"`
	ComicTitle     string  `json:"comicTitle"`
	ChapterID      *string `json:"chapterId"`
	Title          string  `json:"title"`
	MediaType      *string `json:"mediaType"`
	PageNumber     *int    `json:"pageNumber"`
	LifecycleState string  `json:"lifecycleState"`
	Hidden         bool    `json:"hidden"`
	CanChange      bool    `json:"canChange"`
	PreviewURL     *string `json:"previewUrl,omitempty"`
	ContentURL     *string `json:"contentUrl,omitempty"`
}

type ManagedReactionPage struct {
	Items    []ManagedReaction `json:"items"`
	Page     int               `json:"page"`
	PageSize int               `json:"pageSize"`
	Total    int               `json:"total"`
}
