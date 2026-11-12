package domain

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

type BrowseQuery struct {
	Page, PageSize int
	Keyword        string
	CategoryID     string
	Uncategorized  bool
	TagIDs         []string
	TagMode        string
	HQStatus       string
	LQStatus       string
	Attention      string
	Sort           string
	Order          string
}
type Operation struct {
	CanSubmit bool     `json:"canSubmit"`
	Blockers  []string `json:"blockers"`
}
type Operations struct {
	RefreshMetadata Operation `json:"refreshMetadata"`
	Transcode       Operation `json:"transcode"`
	GenerateLQ      Operation `json:"generateLq"`
	ReleaseHQ       Operation `json:"releaseHq"`
}
type OperationFacts struct {
	ActiveMetadata, IncompleteMetadata                              int
	ActiveUpload, IncompleteUpload                                  int
	ActiveTranscode, IncompleteTranscode                            int
	ActiveExport                                                    int
	Tasks                                                           []jobsdomain.Task
	Images                                                          mediadomain.HQImageCounts
	ActiveLQ, ActiveHQ, ActiveMedia, IncompleteRelease, ActiveTasks int
}
type StorageObject struct {
	ActiveTasks []TaskActivity `json:"activeTasks"`
	ID          string         `json:"id"`
	ComicID     string         `json:"comicId"`
	Title       string         `json:"title"`
	GlobalOrder *int           `json:"globalOrder"`
	Inventory   Inventory      `json:"inventory"`
	TotalBytes  int64          `json:"totalBytes"`
	HQStatus    string         `json:"hqStatus"`
	LQStatus    string         `json:"lqStatus"`
	Operations  Operations     `json:"operations"`
	Facts       OperationFacts `json:"-"`
}
type StoragePage struct {
	Items    []StorageObject `json:"items"`
	Page     int             `json:"page"`
	PageSize int             `json:"pageSize"`
	Total    int             `json:"total"`
}

type TaskActivity struct {
	ID                 string  `json:"id"`
	Type               string  `json:"type"`
	TargetType         string  `json:"targetType"`
	TargetID           string  `json:"targetId"`
	Status             string  `json:"status"`
	CanCancel          bool    `json:"canCancel"`
	CancellationReason *string `json:"cancellationReason"`
}
