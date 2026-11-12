package domain

const MaxCoverBytes = 2 << 20
const MaxCoverSourceBytes = 8192

type CoverSource struct {
	EntryID    string          `json:"entryId"`
	MediaID    string          `json:"mediaId"`
	Candidates []FileCandidate `json:"candidates"`
}

type Cover struct {
	SHA256      string
	ContentType string
	Data        []byte
}
