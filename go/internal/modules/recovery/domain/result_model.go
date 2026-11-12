package domain

const (
	ResultRestored    = "restored"
	ResultPlaceholder = "placeholder"
	ResultSkipped     = "skipped"
	ResultEmpty       = "empty"
	ResultError       = "error"
	CodeConflict      = "RECOVERY_CONFLICT"
	CodeMetadata      = "RECOVERY_METADATA_INVALID"
	CodeMissing       = "RECOVERY_SOURCE_MISSING"
	CodeChanged       = "RECOVERY_SOURCE_CHANGED"
	CodeMismatch      = "RECOVERY_CONTENT_MISMATCH"
	CodeUnavailable   = "RECOVERY_TOOL_UNAVAILABLE"
	CodeUnsafe        = "RECOVERY_SOURCE_UNSAFE"
	CodeLimit         = "RECOVERY_SOURCE_LIMIT"
	MaxResultTries    = 1000000
)

type Outcome struct {
	TaskID, ComicID, Status string
	TaskAttempt, TryCount   int
	ErrorCode               *string
	SourceSHA256            *string `json:"-"`
	UpdatedAt               string
}

type ResultSummary struct {
	Total        int `json:"total"`
	Processed    int `json:"processed"`
	Restored     int `json:"restored"`
	Placeholders int `json:"placeholders"`
	Skipped      int `json:"skipped"`
	Empty        int `json:"empty"`
	Failed       int `json:"failed"`
}
