package ports

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
)

type ImageRenderer interface {
	Render(context.Context, mediadomain.ImageRequest) (mediadomain.ImageArtifact, error)
}
