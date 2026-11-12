package ffmpeg

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"time"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/process"
)

type FrameExtractor struct {
	runner     executor
	executable string
}

func NewFrameExtractor(runner *process.Runner, executable string) *FrameExtractor {
	return &FrameExtractor{runner: runner, executable: executable}
}

var _ mediaports.CoverFrames = (*FrameExtractor)(nil)

// Extract 取视频靠前的一帧；优先 2 秒，短视频再回退到更早时间。
func (extractor *FrameExtractor) Extract(ctx context.Context, filename string) ([]byte, error) {
	if extractor.runner == nil || extractor.executable == "" {
		return nil, mediadomain.ErrUnavailable
	}
	if !filepath.IsAbs(filename) {
		return nil, mediadomain.ErrCoverFrame
	}
	info, err := os.Lstat(filename)
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
		return nil, mediadomain.ErrCoverFrame
	}
	directory, err := os.MkdirTemp("", "comicatlas-cover-frame-")
	if err != nil {
		return nil, mediadomain.ErrCoverFrame
	}
	defer os.RemoveAll(directory)
	output := filepath.Join(directory, "frame.jpg")
	for _, seconds := range []int{2, 5, 10, 1} {
		if err = ctx.Err(); err != nil {
			return nil, err
		}
		_, err = extractor.runner.Run(ctx, process.Request{
			Executable: extractor.executable,
			Timeout:    120 * time.Second,
			Args: []string{
				"-nostdin", "-hide_banner", "-v", "error",
				"-protocol_whitelist", "file", "-format_whitelist", "mov,matroska,webm,avi",
				"-ss", strconv.Itoa(seconds), "-i", filename,
				"-frames:v", "1", "-c:v", "mjpeg", "-q:v", "2", "-f", "image2", "-y", output,
			},
		})
		if err != nil {
			if errors.Is(err, process.ErrStart) {
				return nil, mediadomain.ErrUnavailable
			}
			continue
		}
		data, readErr := os.ReadFile(output)
		if readErr == nil && len(data) > 0 {
			return data, nil
		}
	}
	return nil, mediadomain.ErrCoverFrame
}
