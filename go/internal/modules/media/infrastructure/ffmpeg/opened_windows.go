package ffmpeg

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"errors"
	"os"
	"path/filepath"
)

// os.Open 的 Windows 共享模式不含 FILE_SHARE_DELETE。逐层固定路径，随后按身份绑定叶文件。
func openedProbePath(input *os.File) (name string, release func() error, err error) {
	name, err = filepath.Abs(input.Name())
	if err != nil {
		return "", nil, mediadomain.ErrProbe
	}
	paths := []string{name}
	for current := name; ; {
		parent := filepath.Dir(current)
		if parent == current {
			break
		}
		paths = append(paths, parent)
		current = parent
	}
	var pins []*os.File
	release = func() error {
		var err error
		for i := len(pins) - 1; i >= 0; i-- {
			err = errors.Join(err, pins[i].Close())
		}
		pins = nil
		return err
	}
	fail := func() (string, func() error, error) { return "", nil, errors.Join(mediadomain.ErrProbe, release()) }
	for i := len(paths) - 1; i >= 0; i-- {
		before, err := os.Lstat(paths[i])
		if err != nil || before.Mode()&os.ModeSymlink != 0 || i > 0 && !before.IsDir() || i == 0 && !before.Mode().IsRegular() {
			return fail()
		}
		pin, err := os.Open(paths[i])
		if err != nil {
			return fail()
		}
		pins = append(pins, pin)
		after, err := pin.Stat()
		if err != nil || !os.SameFile(before, after) {
			return fail()
		}
	}
	selected, err := input.Stat()
	current, statErr := pins[len(pins)-1].Stat()
	if err != nil || statErr != nil || !os.SameFile(selected, current) {
		return fail()
	}
	return name, release, nil
}
