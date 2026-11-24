package domain

type ImageSource struct {
	MediaID, Path string
	Modified      int64
	Size          int64
}

type ImageCandidate struct {
	Source  ImageSource
	HQState string
	LQPath  *string
	LQSize  *int64
	LQValid *bool
}

type CoverRecord struct {
	MediaID, SourceKey, Path string
	Size                     int64
	Width, Height            int
}
