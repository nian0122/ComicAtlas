package domain

// HQImageCounts 是数据库快照中的图片状态计数，不代表磁盘完整性验证。
type HQImageCounts struct {
	Images, Available, Released, Releasing, Invalid, MissingLQ, MissingSourceSHA256 int
	OriginalBytes                                                                   int64
}

type HQReleasePreview struct {
	Truncated     bool     `json:"truncated"`
	ScopeType     string   `json:"scopeType"`
	ScopeID       string   `json:"scopeId"`
	Allowed       bool     `json:"allowed"`
	Images        int      `json:"images"`
	Available     int      `json:"available"`
	Released      int      `json:"released"`
	OriginalBytes int64    `json:"originalBytes"`
	Blockers      []string `json:"blockers"`
}
type HQReleaseItem struct {
	MediaID       string   `json:"mediaId"`
	Position      int      `json:"position"`
	Status        string   `json:"status"`
	OriginalBytes int64    `json:"originalBytes"`
	Outcome       *string  `json:"outcome"`
	ErrorCode     *string  `json:"errorCode"`
	SourcePath    string   `json:"-"`
	SourceSHA256  string   `json:"-"`
	LQ            LQRecord `json:"-"`
}
type HQReleasePage struct {
	Items    []HQReleaseItem `json:"items"`
	Page     int             `json:"page"`
	PageSize int             `json:"pageSize"`
	Total    int             `json:"total"`
}
type HQCurrent struct {
	State, SourcePath, SourceSHA256 string
	Size                            int64
	LQ                              *LQRecord
}
