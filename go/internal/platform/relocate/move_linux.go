package relocate

import (
	"errors"
	"fmt"
	"golang.org/x/sys/unix"
	"os"
	"runtime"
	"syscall"
)

// NoReplace 通过父目录句柄移动对象，禁止覆盖现有目标。
func NoReplace(source, target *os.File, oldName, newName string, expected os.FileInfo) error {
	// 调用方持有来源身份并检查类型；句柄相对操作避免父路径被替换后越界。
	opened, err := unix.Openat(int(source.Fd()), oldName, unix.O_RDONLY|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0)
	if err != nil {
		return err
	}
	file := os.NewFile(uintptr(opened), oldName)
	current, statErr := file.Stat()
	file.Close()
	if statErr != nil {
		return statErr
	}
	if !os.SameFile(expected, current) {
		return os.ErrInvalid
	}
	err = unix.Renameat2(int(source.Fd()), oldName, int(target.Fd()), newName, unix.RENAME_NOREPLACE)
	if errors.Is(err, unix.EINVAL) || errors.Is(err, unix.EOPNOTSUPP) || errors.Is(err, unix.ENOSYS) {
		err = moveReserved(source, target, oldName, newName, expected)
	}
	runtime.KeepAlive(source)
	runtime.KeepAlive(target)
	return err
}
func CrossDevice(err error) bool { return errors.Is(err, unix.EXDEV) }
func LinkUnsupported(err error) bool {
	return errors.Is(err, unix.EOPNOTSUPP) || errors.Is(err, unix.EPERM) || errors.Is(err, unix.ENOSYS)
}
func Identity(file *os.File) (string, error) {
	info, err := file.Stat()
	if err != nil {
		return "", err
	}
	value, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return "", os.ErrInvalid
	}
	return fmt.Sprintf("%d:%d", value.Dev, value.Ino), nil
}
func SyncDirectory(file *os.File) error { return file.Sync() }
