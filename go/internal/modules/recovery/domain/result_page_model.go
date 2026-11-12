package domain

type ResultItem struct {
	ComicID   string  `json:"comicId"`
	Status    string  `json:"status"`
	Attempt   int     `json:"attempt"`
	TryCount  int     `json:"tryCount"`
	ErrorCode *string `json:"errorCode"`
	UpdatedAt string  `json:"updatedAt"`
}

type ResultPage struct {
	TaskID         string         `json:"taskId"`
	Sealed         bool           `json:"sealed"`
	CandidateCount int            `json:"candidateCount"`
	IgnoredCount   int            `json:"ignoredCount"`
	Summary        *ResultSummary `json:"summary"`
	Items          []ResultItem   `json:"items"`
	NextAfter      *string        `json:"nextAfter"`
}
