package domain

// 清理范围统计只汇总允许执行的独立根，不表示已释放的实际字节。
type PurgeSummary struct {
	RootCount       int   `json:"rootCount"`
	CoveredCount    int   `json:"coveredCount"`
	EntryCount      int   `json:"entryCount"`
	FileCount       int   `json:"fileCount"`
	RegisteredBytes int64 `json:"registeredBytes"`
	UnmeasuredFiles int   `json:"unmeasuredFiles"`
	RetentionDays   *int  `json:"retentionDays"`
}
