package domain

const MaxCandidates = 1000
const MaxScanResultBytes = 16 << 20

type ScanTarget struct{ ID, SourcePath, Kind, Name string }
type Candidate struct {
	ID         string `json:"id"`
	SourcePath string `json:"sourcePath"`
	Kind       string `json:"kind"`
	Title      string `json:"title"`
	Selectable bool   `json:"selectable"`
}
type CandidatePage struct {
	Items    []Candidate `json:"items"`
	Page     int         `json:"page"`
	PageSize int         `json:"pageSize"`
	Total    int         `json:"total"`
}
