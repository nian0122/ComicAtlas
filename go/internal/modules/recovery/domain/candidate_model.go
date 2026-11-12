package domain

const (
	CandidateExisting      = "record_exists"
	CandidateEmpty         = "empty_shell"
	CandidateNeedsMetadata = "metadata_missing"
	CandidateBytesVerified = "bytes_verified"
)

// Candidate 是写入前的候选分类，不是恢复任务成功结果。
type Candidate struct {
	ComicID, Kind string
	Document      *Document
	Verification  MediaVerification
	Presence      HQPresence
}
