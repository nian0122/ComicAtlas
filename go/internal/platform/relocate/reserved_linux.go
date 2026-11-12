package relocate

import (
	"errors"
	"os"
	"runtime"

	"golang.org/x/sys/unix"
)

// moveReserved 用排他占位支持不实现 RENAME_NOREPLACE 的挂载文件系统。
// 媒体目标根由单实例管理；已有目标始终报冲突，不通过普通 rename 覆盖它。
func moveReserved(source, target *os.File, oldName, newName string, expected os.FileInfo) error {
	defer runtime.KeepAlive(source)
	defer runtime.KeepAlive(target)
	if !expected.IsDir() {
		return moveReservedFile(source, target, oldName, newName, expected)
	}
	if err := unix.Mkdirat(int(target.Fd()), newName, uint32(expected.Mode().Perm())); err != nil {
		return err
	}
	fd, err := unix.Openat(int(target.Fd()), newName, unix.O_DIRECTORY|unix.O_NOFOLLOW|unix.O_RDONLY|unix.O_CLOEXEC, 0)
	if err != nil {
		return err
	}
	reservation := os.NewFile(uintptr(fd), newName)
	defer reservation.Close()
	reserved, err := reservation.Stat()
	if err != nil {
		return err
	}
	// Docker Desktop 映射的 Windows 文件系统不允许替换仍打开的占位。
	if err := reservation.Close(); err != nil {
		return err
	}
	// 仅替换本次排他创建的空目录，失败时只清理仍属于本次操作的占位。
	current, err := directoryEntry(target, newName)
	if err != nil || !os.SameFile(reserved, current) {
		return errors.Join(os.ErrExist, err)
	}
	if err = unix.Renameat(int(source.Fd()), oldName, int(target.Fd()), newName); err != nil {
		current, statErr := directoryEntry(target, newName)
		if statErr == nil && os.SameFile(reserved, current) {
			return errors.Join(err, unix.Unlinkat(int(target.Fd()), newName, unix.AT_REMOVEDIR))
		}
		return err
	}
	return nil
}

// 文件使用 O_EXCL 占位，不依赖 exFAT 不支持的硬链接。失败不删除来源。
func moveReservedFile(source, target *os.File, oldName, newName string, expected os.FileInfo) error {
	fd, err := unix.Openat(int(target.Fd()), newName, unix.O_CREAT|unix.O_EXCL|unix.O_RDWR|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0600)
	if err != nil {
		return err
	}
	reservation := os.NewFile(uintptr(fd), newName)
	defer reservation.Close()
	owned, err := reservation.Stat()
	if err != nil {
		return err
	}
	if err := reservation.Close(); err != nil {
		return err
	}
	entry := func(parent *os.File, name string) (os.FileInfo, error) {
		fd, err := unix.Openat(int(parent.Fd()), name, unix.O_RDONLY|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0)
		if err != nil {
			return nil, err
		}
		file := os.NewFile(uintptr(fd), name)
		defer file.Close()
		return file.Stat()
	}
	current, err := entry(target, newName)
	if err != nil || !os.SameFile(owned, current) {
		return errors.Join(os.ErrExist, err)
	}
	original, err := entry(source, oldName)
	if err != nil || !os.SameFile(expected, original) {
		current, statErr := entry(target, newName)
		if statErr == nil && os.SameFile(owned, current) {
			_ = unix.Unlinkat(int(target.Fd()), newName, 0)
		}
		return errors.Join(os.ErrInvalid, err)
	}
	if err = unix.Renameat(int(source.Fd()), oldName, int(target.Fd()), newName); err != nil {
		current, statErr := entry(target, newName)
		if statErr == nil && os.SameFile(owned, current) {
			return errors.Join(err, unix.Unlinkat(int(target.Fd()), newName, 0))
		}
		return err
	}
	return nil
}

func directoryEntry(parent *os.File, name string) (os.FileInfo, error) {
	fd, err := unix.Openat(int(parent.Fd()), name, unix.O_DIRECTORY|unix.O_NOFOLLOW|unix.O_RDONLY|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	file := os.NewFile(uintptr(fd), name)
	defer file.Close()
	return file.Stat()
}
