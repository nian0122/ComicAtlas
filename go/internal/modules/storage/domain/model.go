package domain

type Usage struct {
	Files int64 `json:"files"`
	Bytes int64 `json:"bytes"`
}
type Inventory struct {
	ScopeType                 string  `json:"scopeType"`
	ScopeID                   *string `json:"scopeId"`
	Comics                    int64   `json:"comics"`
	Chapters                  int64   `json:"chapters"`
	Images                    int64   `json:"images"`
	Videos                    int64   `json:"videos"`
	HQ                        Usage   `json:"hq"`
	LQ                        Usage   `json:"lq"`
	Covers                    Usage   `json:"covers"`
	CurrentLQImages           int64   `json:"currentLqImages"`
	AvailableImages           int64   `json:"availableImages"`
	AvailableBytes            int64   `json:"availableBytes"`
	MissingLQ                 int64   `json:"missingLq"`
	MissingSourceSHA256Images int64   `json:"missingSourceSha256Images"`
	ReleasedImages            int64   `json:"releasedImages"`
	ReleasingImages           int64   `json:"releasingImages"`
}
type ObservedUsage struct {
	HQ             Usage `json:"hq"`
	LQ             Usage `json:"lq"`
	Covers         Usage `json:"covers"`
	Metadata       Usage `json:"metadata"`
	Temp           Usage `json:"temp"`
	Trash          Usage `json:"trash"`
	Exports        Usage `json:"exports"`
	Retired        Usage `json:"retired"`
	Staging        Usage `json:"staging"`
	Other          Usage `json:"other"`
	Entries        int64 `json:"entries"`
	IgnoredLinks   int64 `json:"ignoredLinks"`
	IgnoredSpecial int64 `json:"ignoredSpecial"`
}
type Observation struct {
	TaskID     string        `json:"taskId"`
	StartedAt  string        `json:"startedAt"`
	FinishedAt string        `json:"finishedAt"`
	Usage      ObservedUsage `json:"usage"`
}
type Refresh struct {
	TaskID         string  `json:"taskId"`
	Status         string  `json:"status"`
	CompletedCount int     `json:"completedCount"`
	ErrorCode      *string `json:"errorCode"`
	CanCancel      bool    `json:"canCancel"`
	CanRetry       bool    `json:"canRetry"`
}
type Statistics struct {
	Inventory   Inventory    `json:"inventory"`
	Observation *Observation `json:"observation"`
	Refresh     *Refresh     `json:"refresh"`
	Device      *DeviceSpace `json:"device"`
}

type DeviceSpace struct {
	TotalBytes     int64 `json:"totalBytes"`
	AvailableBytes int64 `json:"availableBytes"`
}
