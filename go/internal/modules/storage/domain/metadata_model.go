package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"encoding/json"
)

type MediaObservation struct {
	Position    int                     `json:"position"`
	MediaID     *string                 `json:"mediaId"`
	ChapterID   *string                 `json:"chapterId"`
	FileName    string                  `json:"fileName"`
	Status      string                  `json:"status"`
	ErrorCode   *string                 `json:"errorCode"`
	StoragePath string                  `json:"storagePath"`
	SHA256      string                  `json:"sha256"`
	Size        int64                   `json:"size"`
	Inspection  *mediadomain.Inspection `json:"inspection"`
	LQValid     *bool                   `json:"lqValid"`
}
type MetadataRun struct {
	ObservedAt   string
	Baseline     librarydomain.Snapshot
	Next         *librarydomain.Snapshot
	Observations []MediaObservation
	Applied      bool
}
type MetadataItem struct {
	Position  int     `json:"position"`
	MediaID   *string `json:"mediaId"`
	ChapterID *string `json:"chapterId"`
	FileName  string  `json:"fileName"`
	Status    string  `json:"status"`
	ErrorCode *string `json:"errorCode"`
}
type MetadataPage struct {
	Items    []MetadataItem `json:"items"`
	Page     int            `json:"page"`
	PageSize int            `json:"pageSize"`
	Total    int            `json:"total"`
}
type MetadataSidecar struct {
	Producer       string                 `json:"producer,omitempty"`
	SyncRevision   int64                  `json:"syncRevision,omitempty"`
	ObservedAt     string                 `json:"observedAt"`
	Version        int                    `json:"version"`
	TaskID         string                 `json:"taskId"`
	BaselineSHA256 string                 `json:"baselineSha256"`
	Snapshot       librarydomain.Snapshot `json:"snapshot"`
	Observations   []MediaObservation     `json:"observations"`
}

type AutomaticMetadataSidecar struct {
	Producer       string          `json:"producer"`
	SyncRevision   int64           `json:"syncRevision"`
	ObservedAt     string          `json:"observedAt"`
	Version        int             `json:"version"`
	TaskID         string          `json:"taskId"`
	BaselineSHA256 string          `json:"baselineSha256"`
	Snapshot       json.RawMessage `json:"snapshot"`
}
