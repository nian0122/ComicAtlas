package domain

type Placeholder struct {
	ComicID   string     `json:"comicId"`
	TaskID    *string    `json:"taskId"`
	Reason    string     `json:"reason"`
	CreatedAt string     `json:"createdAt"`
	Presence  HQPresence `json:"presence"`
}

type PlaceholderPlan struct {
	ComicID, TaskID, Title, CreatedAt string
	Presence                          HQPresence
}
