package filesystem

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	recoveryports "comicatlas.local/backend/internal/modules/recovery/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"os"
)

type VideoFiles struct {
	*Files
	inspector recoveryports.OpenedVideo
}

func NewVideo(files *Files, inspector recoveryports.OpenedVideo) *VideoFiles {
	return &VideoFiles{Files: files, inspector: inspector}
}

var _ recoveryports.Sources = (*VideoFiles)(nil)

func (files *VideoFiles) MeasureHQVideo(ctx context.Context, comic, name string) (recoverydomain.Measurement, error) {
	if err := recoverydomain.ValidateHQPath(comic, name); err != nil {
		return recoverydomain.Measurement{}, err
	}
	return files.measure(ctx, name, func(ctx context.Context, input *os.File) (mediadomain.Video, error) {
		if files.inspector == nil {
			return mediadomain.Video{}, mediadomain.ErrUnavailable
		}
		return files.inspector.InspectOpened(ctx, input, name)
	})
}
