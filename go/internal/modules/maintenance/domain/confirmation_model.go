package domain

// HQSummary 只统计预览时可执行条目的登记长度，不代表设备实际空闲增量。
type HQSummary struct {
	Images        int    `json:"images"`
	OriginalBytes int64  `json:"originalBytes"`
	Effect        string `json:"effect"`
}

type Confirmation struct {
	Confirm bool   `json:"confirm"`
	Token   string `json:"confirmationToken,omitempty"`
}
