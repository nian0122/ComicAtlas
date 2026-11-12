package domain

import "time"

type DirectoryPreflightRequest struct {
	ID, TokenHash string
	ComicIDs      []string
	CreatedAt     time.Time
}

type DirectoryPreflightView struct {
	ID           string     `json:"id"`
	TaskID       string     `json:"taskId"`
	Status       string     `json:"status"`
	Confirmation string     `json:"confirmationToken,omitempty"`
	Ready        bool       `json:"ready"`
	BookCount    int        `json:"bookCount"`
	ExpiresAt    *time.Time `json:"expiresAt,omitempty"`
	FileCount    *int       `json:"fileCount,omitempty"`
	Bytes        *int64     `json:"bytes,omitempty"`
	MoveTaskID   string     `json:"moveTaskId,omitempty"`
}
