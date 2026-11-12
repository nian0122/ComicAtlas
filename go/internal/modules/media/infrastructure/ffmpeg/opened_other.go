//go:build !linux && !windows

package ffmpeg

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"os"
)

func openedProbePath(*os.File) (string, func() error, error) {
	return "", nil, mediadomain.ErrUnavailable
}
