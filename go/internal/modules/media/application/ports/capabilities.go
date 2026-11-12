package ports

import (
	"context"

	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

type CapabilityReader interface {
	Read(context.Context) (mediadomain.Capabilities, error)
}
