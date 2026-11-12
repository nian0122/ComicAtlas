package domain

type Target struct {
	Type      string `json:"targetType"`
	ID        string `json:"targetId"`
	ComicID   string `json:"comicId"`
	ChapterID string `json:"chapterId"`
	Title     string `json:"title"`
	State     string `json:"-"`
}

type Entry struct {
	ID        string  `json:"id"`
	Target    Target  `json:"target"`
	State     string  `json:"state"`
	Origin    string  `json:"origin"`
	CreatedAt string  `json:"createdAt"`
	UpdatedAt string  `json:"updatedAt"`
	TaskID    string  `json:"taskId"`
	CoverURL  *string `json:"coverUrl,omitempty"`
}

type Request struct {
	Operation    string `json:"operation"`
	EntryID      string `json:"entryId"`
	Confirmation string `json:"confirmation,omitempty"`
}

type Run struct {
	Entry            Entry
	Operation        string
	Candidates       []FileCandidate
	PurgeItems       []PurgeItem
	TrashMoveStarted bool
}

type EntryQuery struct {
	Page     int
	PageSize int
	State    string
	Keyword  string
}

type EntryPage struct {
	Items    []Entry `json:"items"`
	Page     int     `json:"page"`
	PageSize int     `json:"pageSize"`
	Total    int     `json:"total"`
}
