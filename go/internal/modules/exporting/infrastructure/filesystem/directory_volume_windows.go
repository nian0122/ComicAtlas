package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"os"
	"syscall"
)

func sameDirectoryVolume(source, target *os.File) error {
	var left, right syscall.ByHandleFileInformation
	if err := syscall.GetFileInformationByHandle(syscall.Handle(source.Fd()), &left); err != nil {
		return err
	}
	if err := syscall.GetFileInformationByHandle(syscall.Handle(target.Fd()), &right); err != nil {
		return err
	}
	if left.VolumeSerialNumber != right.VolumeSerialNumber {
		return exportdomain.ErrConflict
	}
	return nil
}
