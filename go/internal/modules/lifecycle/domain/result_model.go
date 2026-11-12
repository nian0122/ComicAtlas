package domain

const MaxFileResultBatch = 128

type FileResult struct {
	Position   int    `json:"position"`
	EntryID    string `json:"entryId"`
	Role       string `json:"role"`
	FileName   string `json:"fileName"`
	Operation  string `json:"operation"`
	Phase      string `json:"phase"`
	State      string `json:"state"`
	ErrorCode  string `json:"errorCode,omitempty"`
	ObservedAt string `json:"observedAt"`
}

type FileResultPage struct {
	Items          []FileResult `json:"items"`
	TaskID         string       `json:"taskId"`
	TaskStatus     string       `json:"taskStatus"`
	TaskAttempt    int          `json:"taskAttempt"`
	Attempt        int          `json:"attempt"`
	Operation      string       `json:"operation"`
	Recorded       bool         `json:"recorded"`
	Complete       bool         `json:"complete"`
	TotalFiles     *int         `json:"totalFiles"`
	CompletedFiles int          `json:"completedFiles"`
	FailedFiles    int          `json:"failedFiles"`
	Page           int          `json:"page"`
	PageSize       int          `json:"pageSize"`
	Total          int          `json:"total"`
}
