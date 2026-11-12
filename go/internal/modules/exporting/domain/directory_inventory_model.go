package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"encoding/json"
)

const MaxDirectoryComicInfoBytes = 1 << 20
const MaxDirectoryVideoBytes = 16 << 10
const MaxDirectoryRetiredMetadataBytes = 64 << 10

// DirectoryInventory 保存脱管前的一致业务事实，包含隐藏记录；不是活动媒体归档快照。
type DirectoryInventory struct {
	CoverSelection   *mediadomain.CoverSelection `json:"coverSelection,omitempty"`
	Cover            *DirectoryCover             `json:"cover,omitempty"`
	Version          int                         `json:"version"`
	Comic            librarydomain.Comic         `json:"comic"`
	ComicInfo        *librarydomain.ComicInfo    `json:"sourceComicInfo"`
	StructureVersion int                         `json:"structureVersion"`
	Catalogs         []librarydomain.Catalog     `json:"catalogs"`
	Chapters         []DirectoryChapter          `json:"chapters"`
	Media            []DirectoryMedia            `json:"media"`
	SuppressedPaths  []string                    `json:"suppressedPaths,omitempty"`
	Retired          []DirectoryRetired          `json:"retired"`
	Trash            []DirectoryTrash            `json:"trash"`
	Reactions        []DirectoryReaction         `json:"reactions"`
	Progress         *librarydomain.Progress     `json:"progress"`
}

type DirectoryChapter struct {
	librarydomain.Chapter
	State string `json:"state"`
}

type DirectoryStoredFile struct {
	Path   string `json:"path"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

type DirectoryMedia struct {
	librarydomain.StoredMedia
	State string `json:"state"`
}

type DirectoryRetired struct {
	OriginalPath     string              `json:"originalPath"`
	OriginalMetadata json.RawMessage     `json:"originalMetadata,omitempty"`
	SessionID        string              `json:"sessionId"`
	MediaID          string              `json:"mediaId"`
	Kind             string              `json:"kind"`
	File             DirectoryStoredFile `json:"file"`
}

// Trash 的物理文件必须随后以匹配的文件检查点解析，不能把原路径直接当作仍在的原件。
type DirectoryTrash struct {
	State      string                          `json:"state"`
	ID         string                          `json:"id"`
	TargetType string                          `json:"targetType"`
	TargetID   string                          `json:"targetId"`
	ChapterID  string                          `json:"chapterId"`
	Title      string                          `json:"title"`
	CreatedAt  string                          `json:"createdAt"`
	UpdatedAt  string                          `json:"updatedAt"`
	TaskID     string                          `json:"taskId"`
	Candidates []lifecycledomain.FileCandidate `json:"candidates"`
}

type DirectoryReaction struct {
	TargetType string `json:"targetType"`
	TargetID   string `json:"targetId"`
	Reaction   string `json:"reaction"`
	UpdatedAt  string `json:"updatedAt"`
}

type DirectoryInventorySnapshot struct {
	MutationVersion int64
	Inventory       DirectoryInventory
	SHA256          string
	CanonicalBytes  int
}

// DirectoryActivity 是事务内采集的阻塞事实；是否允许移出由纯规则判断。
type DirectoryActivity struct {
	Tasks              int64
	Uploads            int64
	LifecycleRuns      int64
	RefreshRuns        int64
	PreparedTranscodes int64
	PendingRetired     int64
}

type DirectoryCover struct {
	MediaID   string              `json:"mediaId"`
	File      DirectoryStoredFile `json:"file"`
	SourceKey string              `json:"sourceKey"`
	Width     int                 `json:"width"`
	Height    int                 `json:"height"`
}
