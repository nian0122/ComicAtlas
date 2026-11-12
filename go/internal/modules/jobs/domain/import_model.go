package domain

const MaxImportBatchItems = 1000

type ImportInput struct{ Type, SourcePath, Title, ReferenceID string }
type PreparedImport struct {
	ReferenceID string
	Resource    ResourceTask
	Explicit    bool
	RequestHash string
}
type BatchTask struct {
	ReferenceID string  `json:"referenceId"`
	TaskID      string  `json:"taskId"`
	Status      string  `json:"status"`
	ErrorCode   *string `json:"errorCode"`
}
type TaskBatch struct {
	ID        string      `json:"id"`
	TaskID    string      `json:"taskId"`
	Status    string      `json:"status"`
	Total     int         `json:"total"`
	Completed int         `json:"completed"`
	Items     []BatchTask `json:"items"`
}
