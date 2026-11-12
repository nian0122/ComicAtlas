package ports

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"os"
)

type OpenedVideo interface {
	InspectOpened(context.Context, *os.File, string) (mediadomain.Video, error)
}
