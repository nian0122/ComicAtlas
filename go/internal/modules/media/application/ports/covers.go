package ports

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"os"
)

type CoverRepository interface {
	CoverCandidates(context.Context, string) ([]mediadomain.CoverCandidate, error)
	ImageSource(context.Context, string) (mediadomain.ImageSource, error)
	CoverSource(context.Context, string) (mediadomain.ImageSource, error)
	Cover(context.Context, string) (*mediadomain.CoverRecord, error)
	SaveCover(context.Context, string, mediadomain.ImageSource, mediadomain.CoverRecord) error
	SaveFrameCover(context.Context, string, mediadomain.CoverRecord) error
	SetCoverSelection(context.Context, string, string) error
	CoverSelection(context.Context, string) (mediadomain.CoverSelection, error)
	CoverSelectionRead(context.Context, string) (*mediadomain.CoverSelection, error)
	CoverMediaActive(context.Context, string, string) (bool, error)
	SelectCover(context.Context, string, int, string) (mediadomain.CoverSelection, error)
	BindImageSource(context.Context, mediadomain.ImageSource, mediadomain.ImageSource) error
}
type CoverStorage interface {
	BuildCover(context.Context, string, mediadomain.ImageSource) (mediadomain.CoverRecord, error)
	BuildCoverFromFrame(context.Context, string, string, []byte) (mediadomain.CoverRecord, error)
	OpenCover(context.Context, mediadomain.CoverRecord) (*os.File, error)
	OpenStoredCover(context.Context, string) (*os.File, string, error)
	MediaPath(string) (string, error)
	ResolveImageSource(context.Context, mediadomain.ImageSource) (mediadomain.ImageSource, error)
}

type CoverFrames interface {
	Extract(context.Context, string) ([]byte, error)
}
