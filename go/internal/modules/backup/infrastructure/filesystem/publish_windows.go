package filesystem

import (
	"os"
	"path/filepath"

	"golang.org/x/sys/windows"
)

func publishNoReplace(parent *os.Root, old, next string) error {
	from, err := windows.UTF16PtrFromString(filepath.Join(parent.Name(), old))
	if err != nil {
		return err
	}
	to, err := windows.UTF16PtrFromString(filepath.Join(parent.Name(), next))
	if err != nil {
		return err
	}
	// 不使用 REPLACE_EXISTING 或跨卷复制，目标出现时必须拒绝。
	err = windows.MoveFileEx(from, to, 0)
	if err == windows.ERROR_ALREADY_EXISTS || err == windows.ERROR_FILE_EXISTS {
		return os.ErrExist
	}
	return err
}
