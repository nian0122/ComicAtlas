package domain

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"encoding/json"
)

const MaxSnapshotMedia = 100000
const MaxSnapshotBytes = 32 << 20

type StoredLQ struct {
	mediadomain.LQRecord
	StoragePath string `json:"storagePath"`
}
type StoredMedia struct {
	Media
	Modified    int64     `json:"modified,omitempty"`
	StoragePath string    `json:"storagePath"`
	LQFile      *StoredLQ `json:"lqFile"`
}
type Snapshot struct {
	CoverSelection  *mediadomain.CoverSelection `json:"coverSelection,omitempty"`
	SuppressedPaths []string                    `json:"suppressedPaths,omitempty"`
	Version         int                         `json:"version"`
	Detail          Detail                      `json:"detail"`
	Media           []StoredMedia               `json:"media"`
}

// SidecarSnapshot 在一致读事务内生成；离开事务后不再持有整份对象图。
type SidecarSnapshot struct {
	ComicID  string
	Revision int64
	Data     json.RawMessage
}

type StorageDelta struct {
	Added    []StoredMedia
	Changed  []StoredMedia
	Chapters []Chapter
}
