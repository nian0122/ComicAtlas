package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"comicatlas.local/backend/internal/platform/relocate"
	"golang.org/x/sys/unix"
	"os"
	"runtime"
)

const directoryPublishSupported = true

func renameDirectoryNoReplace(source, target *os.File, name string, expected os.FileInfo) error {
	fd, err := unix.Openat(int(source.Fd()), name, unix.O_RDONLY|unix.O_DIRECTORY|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0)
	if err != nil {
		return err
	}
	opened := os.NewFile(uintptr(fd), name)
	defer opened.Close()
	actual, err := opened.Stat()
	if err != nil {
		return err
	}
	if !actual.IsDir() || !os.SameFile(expected, actual) {
		return exportdomain.ErrManifest
	}
	err = relocate.NoReplace(source, target, name, name, expected)
	runtime.KeepAlive(source)
	runtime.KeepAlive(target)
	return err
}
