//go:build windows

package diskspace

import (
	"errors"
	"math"

	"golang.org/x/sys/windows"
)

func Measure(path string) (Snapshot, error) {
	name, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return Snapshot{}, err
	}
	var available, total, free uint64
	if err = windows.GetDiskFreeSpaceEx(name, &available, &total, &free); err != nil {
		return Snapshot{}, err
	}
	if total > math.MaxInt64 || available > total {
		return Snapshot{}, errors.New("文件系统容量无效")
	}
	return Snapshot{Total: int64(total), Available: int64(available)}, nil
}
