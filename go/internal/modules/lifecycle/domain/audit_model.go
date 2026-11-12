package domain

type AuditEntry struct {
	Entry       Entry
	Candidates  []FileCandidate
	PlanHash    string
	ObjectState string
}

type AuditSnapshot struct {
	Requested       Entry
	OwnerTaskID     string
	OwnerState      string
	OwnerAttempt    int
	OwnerFinishedAt *string
	Operation       string
	Superseded      bool
	Entries         []AuditEntry
}

type JournalObservation struct {
	State        string
	Phase        string
	MoveConflict bool
}

type FileObservation struct {
	Position         int    `json:"position"`
	EntryID          string `json:"entryId"`
	Role             string `json:"role"`
	FileName         string `json:"fileName"`
	SourceState      string `json:"sourceState"`
	RetainedState    string `json:"retainedState"`
	SameFile         bool   `json:"sameFile"`
	Prepared         bool   `json:"prepared"`
	InitiallyMissing bool   `json:"initiallyMissing"`
	JournalState     string `json:"journalState"`
	JournalPhase     string `json:"journalPhase"`
	Consistent       bool   `json:"consistent"`
	Resumable        bool   `json:"resumable"`
	ErrorCode        string `json:"errorCode,omitempty"`
}

type AuditSummary struct {
	TaskID          string  `json:"taskId"`
	EntryID         string  `json:"entryId"`
	OwnerTaskID     string  `json:"ownerTaskId"`
	Operation       string  `json:"operation"`
	EntryState      string  `json:"entryState"`
	OwnerState      string  `json:"ownerState"`
	TotalFiles      int     `json:"totalFiles"`
	CheckedFiles    int     `json:"checkedFiles"`
	IssueFiles      int     `json:"issueFiles"`
	EntryIssues     int     `json:"entryIssues"`
	Complete        bool    `json:"complete"`
	Consistent      bool    `json:"consistent"`
	RepairAvailable bool    `json:"repairAvailable"`
	Superseded      bool    `json:"superseded"`
	ObservedAt      *string `json:"observedAt"`
}

type AuditPage struct {
	Items    []FileObservation `json:"items"`
	TaskID   string            `json:"taskId"`
	Complete bool              `json:"complete"`
	Page     int               `json:"page"`
	PageSize int               `json:"pageSize"`
	Total    int               `json:"total"`
}
