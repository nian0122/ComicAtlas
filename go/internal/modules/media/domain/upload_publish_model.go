package domain

type UploadPublishFile struct {
	ComicID   string      `json:"-"`
	ChapterID string      `json:"-"`
	FileID    string      `json:"fileId"`
	MediaID   string      `json:"mediaId"`
	Position  int         `json:"position"`
	Status    string      `json:"status"`
	ErrorCode *string     `json:"errorCode"`
	Name      string      `json:"-"`
	Size      int64       `json:"-"`
	SHA256    string      `json:"-"`
	Info      *Inspection `json:"-"`
}
type UploadPublishPage struct {
	Items    []UploadPublishFile `json:"items"`
	Page     int                 `json:"page"`
	PageSize int                 `json:"pageSize"`
	Total    int                 `json:"total"`
}
type UploadRetired struct {
	Kind, Path, SHA256 string
	Size               int64
}
type UploadPrior struct {
	Name, MediaType, ContentType string
	Previous                     UploadPrevious
	Width, Height                *int
	Video                        *Video
	LQ                           *LQRecord
}
