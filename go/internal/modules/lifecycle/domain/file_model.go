package domain

const MaxFiles = 100000
const MaxJournalBytes = 32 << 20
const MaxFileBytes = 20 << 30
const JournalVersion = 3

type FileCandidate struct {
	Path         string `json:"path"`
	Size         int64  `json:"size"`
	SHA256       string `json:"sha256"`
	SourceAbsent bool   `json:"sourceAbsent,omitempty"`
}

type RetainedFile struct {
	Source  FileCandidate `json:"source"`
	Size    int64         `json:"size"`
	SHA256  string        `json:"sha256"`
	Missing bool          `json:"missing"`
}

type FileJournal struct {
	Version int            `json:"version"`
	ID      string         `json:"id"`
	Phase   string         `json:"phase"`
	Files   []RetainedFile `json:"files"`
	Moves   []MoveUnit     `json:"moves"`
}

type MoveUnit struct {
	Path      string `json:"path"`
	Directory bool   `json:"directory,omitempty"`
	Proof     string `json:"proof"`
	Size      int64  `json:"size,omitempty"`
}
