package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"golang.org/x/sys/unix"
	"os"
	"runtime"
)

// 同一设备的不同 bind mount 也可能拒绝 rename/link，必须核对实际句柄的挂载身份。
func sameDirectoryVolume(source, target *os.File) error {
	var left, right unix.Statx_t
	flags := unix.AT_EMPTY_PATH | unix.AT_STATX_DONT_SYNC
	if err := unix.Statx(int(source.Fd()), "", flags, unix.STATX_MNT_ID, &left); err != nil {
		return err
	}
	if err := unix.Statx(int(target.Fd()), "", flags, unix.STATX_MNT_ID, &right); err != nil {
		return err
	}
	runtime.KeepAlive(source)
	runtime.KeepAlive(target)
	if left.Mask&unix.STATX_MNT_ID == 0 || right.Mask&unix.STATX_MNT_ID == 0 {
		return exportdomain.ErrStorage
	}
	if left.Mnt_id != right.Mnt_id || left.Dev_major != right.Dev_major || left.Dev_minor != right.Dev_minor {
		return exportdomain.ErrConflict
	}
	return nil
}
