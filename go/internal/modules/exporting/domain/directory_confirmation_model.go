package domain

import (
	"bytes"
	"time"
)

const MaxDirectoryPreviewBytes = 64 << 20
const MaxDirectoryPreviews = 4
const DirectoryPreviewTTL = 5 * time.Minute

// DirectoryPreviewRecord 只持久化确认摘要，不保存原始确认凭据。
type DirectoryPreviewRecord struct {
	ID, TokenHash, Data, SHA256 string
	CreatedAt, ExpiresAt        time.Time
	TaskID                      string
}

type DirectoryPreviewView struct {
	ID           string    `json:"id"`
	Confirmation string    `json:"confirmationToken"`
	ExpiresAt    time.Time `json:"expiresAt"`
	BookCount    int       `json:"bookCount"`
	FileCount    int       `json:"fileCount"`
	Bytes        int64     `json:"bytes"`
}

type DirectoryPreviewRequest struct {
	ComicIDs []string `json:"comicIds"`
}

type DirectoryConfirmRequest struct {
	Confirm           bool   `json:"confirm"`
	ConfirmationToken string `json:"confirmationToken"`
}

// 冻结协议逐行存储，数组先声明有界条数，避免一次编码/解码整个大批次。
type directoryPreviewHeader struct {
	Version int
	Books   int
	Seal    DirectorySeal
}

type directoryPreviewBook struct {
	Identity                                              DirectoryInventoryIdentity
	Name                                                  string
	Files, Directories, Owned, Absences, Documents, Trash int
}

type directoryPreviewBuffer struct{ value bytes.Buffer }
