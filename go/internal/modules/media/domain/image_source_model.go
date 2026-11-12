package domain

type ImageSource struct {
	MediaID, Path, SHA256, OriginalSHA256 string
	Size                                  int64
}

type ImageCandidate struct {
	MetadataOnly                     bool
	Source                           ImageSource
	HQState                          string
	LQPath, LQSHA256, LQSourceSHA256 *string
	LQSize                           *int64
	LQValid                          *bool
	LQTaskID                         string
	LQProvenance                     *LQProvenance
}

type CoverRecord struct {
	MediaID, SourceKey, Path, SHA256 string
	Size                             int64
	Width, Height                    int
}
