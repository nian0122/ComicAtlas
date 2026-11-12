//go:build !windows && !linux

package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"os"
	"syscall"
)

func sameDirectoryVolume(source, target *os.File) error {
	left, err := source.Stat()
	if err != nil {
		return err
	}
	right, err := target.Stat()
	if err != nil {
		return err
	}
	a, aok := left.Sys().(*syscall.Stat_t)
	b, bok := right.Sys().(*syscall.Stat_t)
	if !aok || !bok || a.Dev != b.Dev {
		return exportdomain.ErrConflict
	}
	return nil
}
