package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

const MaxPendingPreviews = 32

type ItemOutcome struct {
	Status         string
	ErrorCode      *string
	UpdatedVersion *int
}

type Request struct {
	TargetSelection *lifecycledomain.TargetSelection `json:"targetSelection,omitempty"`
	TrashSelection  *lifecycledomain.EntrySelection  `json:"trashSelection,omitempty"`
	LQ              *mediadomain.LQRequest           `json:"lq,omitempty"`
	Operation       string                           `json:"operation"`
	Selection       librarydomain.ComicSelection     `json:"selection"`
	Patch           librarydomain.MetadataPatch      `json:"patch"`
}

// BatchOptions 不携带选择器；执行仅消费已冻结条目，不能重新解析选择范围。
type BatchOptions struct {
	LQ        *mediadomain.LQRequest      `json:"lq,omitempty"`
	Operation string                      `json:"operation"`
	Patch     librarydomain.MetadataPatch `json:"patch"`
}
type Preview struct {
	Purge              *PurgeSummary               `json:"purge,omitempty"`
	ConfirmationEffect string                      `json:"confirmationEffect,omitempty"`
	ConfirmationToken  string                      `json:"confirmationToken,omitempty"`
	HQ                 *HQSummary                  `json:"hq,omitempty"`
	LQ                 *mediadomain.LQRequest      `json:"lq,omitempty"`
	ActiveCount        int                         `json:"activeCount"`
	ID                 string                      `json:"id"`
	Operation          string                      `json:"operation"`
	Patch              librarydomain.MetadataPatch `json:"patch"`
	SelectedCount      int                         `json:"selectedCount"`
	EligibleCount      int                         `json:"eligibleCount"`
	BlockedCount       int                         `json:"blockedCount"`
	PendingCount       int                         `json:"pendingCount"`
	SucceededCount     int                         `json:"succeededCount"`
	FailedCount        int                         `json:"failedCount"`
	CancelledCount     int                         `json:"cancelledCount"`
	CreatedAt          string                      `json:"createdAt"`
	ExpiresAt          string                      `json:"expiresAt"`
	TaskID             *string                     `json:"taskId"`
	TaskStatus         *string                     `json:"taskStatus"`
	CompletedAt        *string                     `json:"completedAt"`
}
type Record struct {
	ConfirmationHash string
	Preview          Preview
	OptionsJSON      string
}
type Item struct {
	TargetStamp     string                        `json:"targetStamp,omitempty"`
	ChapterID       string                        `json:"chapterId,omitempty"`
	TargetFiles     *int                          `json:"targetFiles,omitempty"`
	Purge           *lifecycledomain.PurgePreview `json:"purge,omitempty"`
	PurgePolicyDays int                           `json:"-"`
	CoveredBy       *int                          `json:"coveredBy,omitempty"`
	CoveredTaskID   *string                       `json:"coveredTaskId,omitempty"`
	EntryID         string                        `json:"entryId,omitempty"`
	EntryStamp      string                        `json:"entryStamp,omitempty"`
	TargetType      string                        `json:"targetType"`
	TargetID        string                        `json:"targetId"`
	Dependencies    []int                         `json:"dependencies,omitempty"`
	HQ              *mediadomain.HQReleasePreview `json:"hq,omitempty"`
	ScopeRevision   int64                         `json:"scopeRevision"`
	ImageCount      int                           `json:"imageCount"`
	VideoCount      int                           `json:"videoCount"`
	ChildTaskID     *string                       `json:"childTaskId"`
	WaitReason      *string                       `json:"waitReason"`
	Position        int                           `json:"position"`
	ComicID         string                        `json:"comicId"`
	Title           string                        `json:"title"`
	Version         int                           `json:"version"`
	Status          string                        `json:"status"`
	ErrorCode       *string                       `json:"errorCode"`
	UpdatedVersion  *int                          `json:"updatedVersion"`
}
type ItemPage struct {
	Items    []Item `json:"items"`
	Total    int    `json:"total"`
	Page     int    `json:"page"`
	PageSize int    `json:"pageSize"`
}
