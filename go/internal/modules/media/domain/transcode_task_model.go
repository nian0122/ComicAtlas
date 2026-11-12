package domain

type TranscodeScope struct{ Type, ID, ComicID, Title string }
type TranscodeItem struct {
	MediaID      string           `json:"mediaId"`
	Position     int              `json:"position"`
	Status       string           `json:"status"`
	ErrorCode    *string          `json:"errorCode"`
	SourcePath   string           `json:"-"`
	SourceName   string           `json:"-"`
	SourceSHA256 string           `json:"-"`
	SourceSize   int64            `json:"-"`
	Output       *TranscodeRecord `json:"-"`
}
type TranscodeRecord struct {
	StagePath string `json:"stagePath"`
	Path      string `json:"path"`
	Size      int64  `json:"size"`
	SHA256    string `json:"sha256"`
	Video     Video  `json:"video"`
}
type TranscodePage struct {
	Items    []TranscodeItem `json:"items"`
	Page     int             `json:"page"`
	PageSize int             `json:"pageSize"`
	Total    int             `json:"total"`
}

const MaxTranscodeItems = 10000
