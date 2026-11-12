package domain

type CoverCandidate struct {
	MediaID      string
	MediaType    string
	FileName     string
	RelativePath string
	GlobalOrder  int
	PageNumber   int
	Depth        int
}
