package ports

import (
	"context"
	"io"

	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
)

// SampleSource 只允许按已冻结媒体标识打开准备好的图片，不接受远端提供的路径。
type SampleSource interface {
	OpenSample(context.Context, string) (io.ReadCloser, error)
}

type Backend interface {
	Analyze(context.Context, aidomain.Request, SampleSource) (aidomain.Candidate, error)
}
