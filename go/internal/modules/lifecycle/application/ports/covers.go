package ports

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
)

type CoverRepository interface {
	Cover(context.Context, string) (lifecycledomain.CoverSource, error)
}

type CoverReader interface {
	ReadCover(context.Context, lifecycledomain.CoverSource) (lifecycledomain.Cover, error)
}
