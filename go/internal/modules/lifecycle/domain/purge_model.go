package domain

import "time"

const ConfirmationLifetime = 5 * time.Minute
const MaxConfirmations = 128

type PurgeItem struct {
	Entry      Entry
	Candidates []FileCandidate
	PlanHash   string
}
type PurgeScope struct {
	Root  Entry
	Items []PurgeItem
}

type PurgePreview struct {
	EntryID         string   `json:"entryId"`
	Allowed         bool     `json:"allowed"`
	Blockers        []string `json:"blockers"`
	RetentionDays   int      `json:"retentionDays"`
	EligibleAt      string   `json:"eligibleAt"`
	EntryCount      int      `json:"entryCount"`
	FileCount       int      `json:"fileCount"`
	RegisteredBytes int64    `json:"registeredBytes"`
	UnmeasuredFiles int      `json:"unmeasuredFiles"`
	Token           string   `json:"token,omitempty"`
	ExpiresAt       string   `json:"expiresAt,omitempty"`
	Stamp           string   `json:"-"`
}

// SelectedPurgeEntry 不保留完整文件清单；覆盖行跟随选中的上层清理根。
type SelectedPurgeEntry struct {
	Entry         Entry
	Preview       *PurgePreview
	CoveredByID   string
	Blocker       string
	RetentionDays int
}

type PurgeSelectionState struct {
	selected map[string]bool
	excluded map[string]bool
	owners   map[string]string
}
