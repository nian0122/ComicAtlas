package ffmpeg

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/process"
)

type executor interface {
	Run(context.Context, process.Request) (process.Result, error)
}
type Prober struct {
	runner     executor
	executable string
}

func New(runner *process.Runner, executable string) *Prober {
	return &Prober{runner: runner, executable: executable}
}

var _ mediaports.Probe = (*Prober)(nil)
var _ mediaports.OpenedProbe = (*Prober)(nil)

func (probe *Prober) Probe(ctx context.Context, filename string) (mediadomain.Probe, error) {
	if probe.executable == "" {
		return mediadomain.Probe{}, mediadomain.ErrUnavailable
	}
	if !filepath.IsAbs(filename) {
		return mediadomain.Probe{}, mediadomain.ErrProbe
	}
	info, err := os.Lstat(filename)
	if err != nil || !info.Mode().IsRegular() {
		return mediadomain.Probe{}, mediadomain.ErrProbe
	}
	return probe.execute(ctx, filename, strings.ToLower(filepath.Ext(filename)))
}

func (probe *Prober) execute(ctx context.Context, filename, extension string) (mediadomain.Probe, error) {
	result, err := probe.runner.Run(ctx, process.Request{Executable: probe.executable, Timeout: 30 * time.Second,
		Args: []string{"-v", "error", "-max_alloc", "16777216", "-threads", "1", "-probesize", "8388608", "-analyzeduration", "5000000",
			"-protocol_whitelist", "file", "-format_whitelist", "mov,matroska,webm,avi", "-max_streams", "64",
			"-show_entries", "format=format_name,duration:stream=codec_type,codec_name,width,height,pix_fmt,duration:stream_disposition=attached_pic",
			"-of", "json", "-i", filename}})
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return mediadomain.Probe{}, err
		}
		if errors.Is(err, process.ErrStart) {
			return mediadomain.Probe{}, mediadomain.ErrUnavailable
		}
		return mediadomain.Probe{}, mediadomain.ErrProbe
	}
	if result.StdoutTruncated {
		return mediadomain.Probe{}, mediadomain.ErrProbe
	}
	var raw report
	if err = json.Unmarshal(result.Stdout, &raw); err != nil {
		return mediadomain.Probe{}, mediadomain.ErrProbe
	}
	value := mediadomain.Probe{Container: raw.Format.Name, Duration: raw.Format.Duration, Extension: extension}
	for _, item := range raw.Streams {
		value.Streams = append(value.Streams, mediadomain.Stream{Type: item.Type, Codec: item.Codec, PixelFormat: item.PixelFormat,
			Width: item.Width, Height: item.Height, Duration: item.Duration, AttachedPicture: item.Disposition.AttachedPicture != 0})
	}
	return value, nil
}
