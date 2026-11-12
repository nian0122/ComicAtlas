package ports

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"os"
)

type Probe interface {
	Probe(context.Context, string) (mediadomain.Probe, error)
}

type OpenedProbe interface {
	ProbeOpened(context.Context, *os.File, string) (mediadomain.Probe, error)
}
