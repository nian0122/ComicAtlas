package domain

const (
	TaskStatusQueued     = "queued"
	TaskStatusRunning    = "running"
	TaskStatusCancelling = "cancelling"
	TaskStatusCancelled  = "cancelled"
	TaskStatusSucceeded  = "succeeded"
	TaskStatusFailed     = "failed"
)

const TimestampFormat = "2006-01-02T15:04:05.000000000Z"
const MaxBatchParents = 4
const MaxBatchChildren = 4

type Task struct {
	CancellationReason *string  `json:"cancellationReason"`
	TargetType         string   `json:"targetType"`
	TargetID           string   `json:"targetId"`
	RequestJSON        *string  `json:"-"`
	Failure            *Failure `json:"failure"`
	CanRetry           bool     `json:"canRetry"`
	ID                 string   `json:"id"`
	Type               string   `json:"type"`
	Status             string   `json:"status"`
	Stage              string   `json:"stage"`
	Attempt            int      `json:"attempt"`
	TotalCount         *int     `json:"totalCount"`
	CompletedCount     int      `json:"completedCount"`
	ErrorCode          *string  `json:"errorCode"`
	ComicID            string   `json:"comicId"`
	CanCancel          bool     `json:"canCancel"`
	CreatedAt          string   `json:"createdAt"`
	StartedAt          *string  `json:"startedAt"`
	FinishedAt         *string  `json:"finishedAt"`
	SourcePath         string   `json:"-"`
	Title              string   `json:"title"`
	Manifest           *string  `json:"-"`
	TitleExplicit      bool     `json:"-"`
}

type Failure struct {
	Message string `json:"message"`
	Action  string `json:"action"`
}

type TaskEvent struct {
	ID             int64   `json:"id"`
	Type           string  `json:"type"`
	Status         string  `json:"status"`
	Stage          string  `json:"stage"`
	Attempt        int     `json:"attempt"`
	TotalCount     *int    `json:"totalCount"`
	CompletedCount int     `json:"completedCount"`
	ErrorCode      *string `json:"errorCode"`
	OccurredAt     string  `json:"occurredAt"`
}

type TaskEventPage struct {
	TaskID    string      `json:"taskId"`
	Items     []TaskEvent `json:"items"`
	NextAfter *int64      `json:"nextAfter"`
}
