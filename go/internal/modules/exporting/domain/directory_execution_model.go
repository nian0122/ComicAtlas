package domain

const MaxDirectoryResultBatch = 64

type DirectoryTaskRun struct {
	TaskID, PreviewID, SHA256 string
	Planning                  DirectoryPlanningResult
	Completed                 bool
}

type DirectoryFileResult struct {
	Position int    `json:"position"`
	ComicID  string `json:"comicId"`
	Source   string `json:"source"`
	Target   string `json:"target"`
	Size     int64  `json:"size"`
	SHA256   string `json:"sha256"`
	State    string `json:"state"`
}

type DirectoryResultPage struct {
	TaskID    string                `json:"taskId"`
	Status    string                `json:"status"`
	Complete  bool                  `json:"complete"`
	Directory *string               `json:"directory"`
	Total     int                   `json:"total"`
	Page      int                   `json:"page"`
	PageSize  int                   `json:"pageSize"`
	Items     []DirectoryFileResult `json:"items"`
}

type DirectoryDetachFacts struct{ TrashIDs []string }
