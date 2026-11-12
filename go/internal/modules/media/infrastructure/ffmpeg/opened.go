package ffmpeg

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// ProbeOpened 在调用期间借用文件；调用者负责根内选择、摘要与完成后来源复核。
func (probe *Prober) ProbeOpened(ctx context.Context, input *os.File, logicalName string) (value mediadomain.Probe, err error) {
	defer func() { runtime.KeepAlive(input) }()
	if err = ctx.Err(); err != nil {
		return value, err
	}
	if probe.executable == "" {
		return value, mediadomain.ErrUnavailable
	}
	if input == nil {
		return value, mediadomain.ErrProbe
	}
	before, err := input.Stat()
	if err != nil || !before.Mode().IsRegular() {
		return value, mediadomain.ErrProbe
	}
	name, release, err := openedProbePath(input)
	if err != nil {
		return value, err
	}
	defer func() {
		err = errors.Join(err, release(), ctx.Err())
		if err != nil {
			value = mediadomain.Probe{}
		}
	}()
	value, err = probe.execute(ctx, name, strings.ToLower(filepath.Ext(logicalName)))
	if err != nil {
		return value, err
	}
	after, err := input.Stat()
	if err != nil || !os.SameFile(before, after) || before.Size() != after.Size() || !before.ModTime().Equal(after.ModTime()) {
		return value, mediadomain.ErrProbe
	}
	return value, nil
}
