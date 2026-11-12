package domain

const MaxLQItems = 10000

type LQRequest struct {
	Force bool      `json:"force"`
	Spec  ImageSpec `json:"spec"`
}
type LQScope struct{ Type, ID, ComicID, Title string }
type LQItem struct {
	MediaID      string  `json:"mediaId"`
	Position     int     `json:"position"`
	Status       string  `json:"status"`
	ErrorCode    *string `json:"errorCode"`
	SourcePath   string  `json:"-"`
	SourceSHA256 string  `json:"-"`
}
type LQItemPage struct {
	Items    []LQItem `json:"items"`
	Page     int      `json:"page"`
	PageSize int      `json:"pageSize"`
	Total    int      `json:"total"`
}
type LQRecord struct {
	MediaID       string `json:"mediaId"`
	SourceSHA256  string `json:"-"`
	Path          string `json:"-"`
	SHA256        string `json:"-"`
	TaskID        string `json:"taskId,omitempty"`
	Size          int64
	Width, Height int
	Spec          *ImageSpec
	Valid         bool
	Provenance    *LQProvenance `json:"provenance,omitempty"`
}

// LQProvenance 声明登记来源，不代表编码任务或由该资料摘要生成的字节。
type LQProvenance struct {
	Origin          string `json:"origin"`
	ReferenceSHA256 string `json:"referenceSha256,omitempty"`
	OriginalState   string `json:"originalState,omitempty"`
}
type LQProgress struct {
	Total, Completed, Failed int
	FirstError               string
}
type LQInfo struct {
	State      string  `json:"state"`
	Available  bool    `json:"available"`
	Width      *int    `json:"width"`
	Height     *int    `json:"height"`
	Size       *int64  `json:"size"`
	ContentURL *string `json:"contentUrl"`
	TaskID     *string `json:"taskId"`
	TaskStatus *string `json:"taskStatus"`
	ErrorCode  *string `json:"errorCode"`
}
