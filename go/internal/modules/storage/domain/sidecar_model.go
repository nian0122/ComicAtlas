package domain

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"time"
)

const SidecarPoll = 2 * time.Second
const MaxSidecarTasks = 4

type SidecarState struct {
	ComicID, Title, RequestedAt                string
	Revision, PublishedRevision                int64
	PublishedAt, TaskID, FinishedAt, ErrorCode *string
	TaskStatus                                 string
	Attempt                                    int
	Blocked                                    bool
}

type SidecarStatus struct {
	ComicID           string              `json:"comicId"`
	State             string              `json:"state"`
	Revision          int64               `json:"revision"`
	PublishedRevision int64               `json:"publishedRevision"`
	PublishedAt       *string             `json:"publishedAt"`
	TaskID            *string             `json:"taskId"`
	RetryAt           *string             `json:"retryAt"`
	ErrorCode         *string             `json:"errorCode"`
	Failure           *jobsdomain.Failure `json:"failure"`
}

type SidecarResult struct {
	TaskID            string  `json:"taskId"`
	ComicID           string  `json:"comicId"`
	RequestedRevision int64   `json:"requestedRevision"`
	ObservedRevision  *int64  `json:"observedRevision"`
	ObservedAt        *string `json:"observedAt"`
	Published         bool    `json:"published"`
}

type SidecarPage struct {
	Items    []SidecarResult `json:"items"`
	Total    int             `json:"total"`
	Page     int             `json:"page"`
	PageSize int             `json:"pageSize"`
}
