package ports

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
)

type MediaFiles interface {
	RequireHQAbsent(context.Context, string, string) error
	MeasureHQ(context.Context, string, string) (recoverydomain.Measurement, error)
	MeasureHQVideo(context.Context, string, string) (recoverydomain.Measurement, error)
	MeasureLQ(context.Context, string) (recoverydomain.Measurement, error)
}

type Sources interface {
	MediaFiles
	ReadSidecar(context.Context, string) (recoverydomain.Document, error)
	HQPresence(context.Context, string) (recoverydomain.HQPresence, error)
}
