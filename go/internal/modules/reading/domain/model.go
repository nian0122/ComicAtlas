package domain

const (
	ReactionLike    = "like"
	ReactionDislike = "dislike"
	ReactionNone    = "none"
)

type Reaction struct {
	TargetType string  `json:"targetType"`
	TargetID   string  `json:"targetId"`
	Value      string  `json:"value"`
	UpdatedAt  *string `json:"updatedAt"`
}

type ReactionChange struct {
	TargetType string `json:"targetType"`
	TargetID   string `json:"targetId"`
	Value      string `json:"value"`
}

type Favorite struct {
	PreviewURL string `json:"previewUrl"`
	Reaction
	ComicID            string  `json:"comicId"`
	ComicTitle         string  `json:"comicTitle"`
	ChapterID          *string `json:"chapterId"`
	PageNumber         *int    `json:"pageNumber"`
	Title              string  `json:"title"`
	MediaType          *string `json:"mediaType"`
	ContentURL         *string `json:"contentUrl"`
	LastReadChapterID  *string `json:"lastReadChapterId"`
	LastReadPageNumber *int    `json:"lastReadPageNumber"`
}

type ReactionPage struct {
	Items    []Favorite `json:"items"`
	Page     int        `json:"page"`
	PageSize int        `json:"pageSize"`
	Total    int        `json:"total"`
}

type HistoryEntry struct {
	Completed    bool   `json:"completed"`
	CoverURL     string `json:"coverUrl"`
	ComicID      string `json:"comicId"`
	Title        string `json:"title"`
	ChapterID    string `json:"chapterId"`
	ChapterTitle string `json:"chapterTitle"`
	PageNumber   int    `json:"pageNumber"`
	MediaID      string `json:"mediaId"`
	ContentURL   string `json:"contentUrl"`
	UpdatedAt    string `json:"updatedAt"`
}

type HistoryPage struct {
	Items    []HistoryEntry `json:"items"`
	Page     int            `json:"page"`
	PageSize int            `json:"pageSize"`
	Total    int            `json:"total"`
}
