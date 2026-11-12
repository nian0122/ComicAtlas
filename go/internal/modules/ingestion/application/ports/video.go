package ports

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
)

type VideoInspector interface {
	Inspect(context.Context, string) (mediadomain.Video, error)
}
