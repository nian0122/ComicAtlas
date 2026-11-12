package domain

import "time"

const UploadChunkBytes int64 = 64 << 20
const MaxUploadFileBytes int64 = 20 << 30
const MaxUploadSessionBytes int64 = 100 << 30
const MaxUploadFiles = 10000
const MaxUploadSessions = 8
const UploadTTL = 24 * time.Hour
const UploadTimeLayout = "2006-01-02T15:04:05.000000000Z"

type UploadFileRequest struct {
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}
type UploadRequest struct {
	ChapterID      string              `json:"chapterId"`
	ReplaceMediaID *string             `json:"replaceMediaId"`
	Files          []UploadFileRequest `json:"files"`
}
type UploadFile struct {
	ID            string `json:"id"`
	Name          string `json:"name"`
	Size          int64  `json:"size"`
	SHA256        string `json:"sha256"`
	ReceivedBytes int64  `json:"receivedBytes"`
	ReceivedParts []int  `json:"receivedParts"`
}
type UploadPrevious struct {
	Path, SHA256, State string
	Size                int64
}
type UploadSession struct {
	CleanupFiles   []UploadRetired `json:"-"`
	TaskID         *string         `json:"taskId"`
	ID             string          `json:"id"`
	ComicID        string          `json:"comicId"`
	ChapterID      string          `json:"chapterId"`
	ReplaceMediaID *string         `json:"replaceMediaId"`
	Status         string          `json:"status"`
	TotalBytes     int64           `json:"totalBytes"`
	ChunkSize      int64           `json:"chunkSize"`
	CreatedAt      string          `json:"createdAt"`
	ExpiresAt      string          `json:"expiresAt"`
	CleanupPending bool            `json:"cleanupPending"`
	Files          []UploadFile    `json:"files"`
	Previous       *UploadPrevious `json:"-"`
}
type UploadRange struct {
	Start, Length, Total int64
	Part                 int
}
type UploadPart struct {
	FileID string `json:"fileId"`
	Part   int    `json:"part"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}
type UploadSpace struct{ Available, Total int64 }
type UploadLimits struct {
	MinFreeBytes   int64
	MinFreePercent int
}
