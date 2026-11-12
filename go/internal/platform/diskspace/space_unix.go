//go:build !windows

package diskspace

import (
	"errors"
	"math"
	"syscall"
)

func Measure(path string) (Snapshot, error) {
	var value syscall.Statfs_t
	if err := syscall.Statfs(path, &value); err != nil {
		return Snapshot{}, err
	}
	if value.Bsize < 1 || value.Blocks > math.MaxInt64/uint64(value.Bsize) || value.Bavail > value.Blocks || value.Bavail > math.MaxInt64/uint64(value.Bsize) {
		return Snapshot{}, errors.New("文件系统容量无效")
	}
	return Snapshot{Total: int64(value.Blocks) * value.Bsize, Available: int64(value.Bavail) * value.Bsize}, nil
}
