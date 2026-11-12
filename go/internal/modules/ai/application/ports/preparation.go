package ports

import (
	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	"context"
)

// PreparedSamples 的调用者应在分析退出后 Close，包括失败和取消路径。
type PreparedSamples interface {
	SampleSource
	Samples() []aidomain.Sample
	Close() error
}

type SamplePreparer interface {
	Prepare(context.Context, []aidomain.SourceImage) (PreparedSamples, error)
}
