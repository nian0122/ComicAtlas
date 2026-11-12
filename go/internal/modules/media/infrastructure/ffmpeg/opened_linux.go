package ffmpeg

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"fmt"
	"os"
)

func openedProbePath(input *os.File) (string, func() error, error) {
	name := fmt.Sprintf("/proc/%d/fd/%d", os.Getpid(), input.Fd())
	selected, err := input.Stat()
	if err != nil {
		return "", nil, mediadomain.ErrProbe
	}
	current, err := os.Stat(name)
	if err != nil || !os.SameFile(selected, current) {
		return "", nil, mediadomain.ErrUnavailable
	}
	return name, func() error { return nil }, nil
}
