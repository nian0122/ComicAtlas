package domain

type AnalysisRequest struct {
	Version          int `json:"version"`
	StructureVersion int `json:"structureVersion"`
	SampleCount      int `json:"sampleCount"`
}

type AnalysisResult struct {
	TaskID      string    `json:"taskId"`
	Request     Request   `json:"request"`
	Candidate   Candidate `json:"candidate"`
	CompletedAt string    `json:"completedAt"`
}

// Run 含本地冻结来源，仅供任务执行，不作为公开响应。
type Run struct {
	TaskID      string
	Snapshot    Snapshot `json:"-"`
	SampleCount int
	Result      *AnalysisResult
}
