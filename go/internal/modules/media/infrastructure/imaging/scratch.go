package imaging

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

const scratchDirectoryPrefix = "image-"

// CleanScratch 仅在持有数据库实例锁且后台任务尚未启动时调用；只清理本组件创建的解码工作区，
// 未知对象阻止启动而不是被删除。
func CleanScratch(ctx context.Context, path string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	path, err := filepath.Abs(path)
	if err != nil {
		return mediadomain.ErrImageIO
	}
	if err = os.MkdirAll(path, 0750); err != nil {
		return mediadomain.ErrImageIO
	}
	path, err = filepath.EvalSymlinks(path)
	if err != nil {
		return mediadomain.ErrImageIO
	}
	root, err := os.OpenRoot(path)
	if err != nil {
		return mediadomain.ErrImageIO
	}
	defer root.Close()
	directory, err := root.Open(".")
	if err != nil {
		return mediadomain.ErrImageIO
	}
	names := make([]string, 0, 8)
	for {
		if err = ctx.Err(); err != nil {
			directory.Close()
			return err
		}
		batch, readErr := directory.ReadDir(64)
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			directory.Close()
			return fmt.Errorf("%w: 读取图片暂存目录项失败", mediadomain.ErrImageIO)
		}
		for _, entry := range batch {
			if len(names) >= 4096 || !validScratchDirectory(entry.Name()) {
				directory.Close()
				return fmt.Errorf("%w: 存在未知图片暂存对象", mediadomain.ErrImageIO)
			}
			info, statErr := root.Lstat(entry.Name())
			if statErr != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
				directory.Close()
				return fmt.Errorf("%w: 图片暂存工作区类型无效", mediadomain.ErrImageIO)
			}
			names = append(names, entry.Name())
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
	}
	if err = directory.Close(); err != nil {
		return fmt.Errorf("%w: 关闭图片暂存目录失败", mediadomain.ErrImageIO)
	}
	for _, name := range names {
		if err = ctx.Err(); err != nil {
			return err
		}
		if err = root.RemoveAll(name); err != nil {
			return fmt.Errorf("%w: 删除图片暂存工作区失败", mediadomain.ErrImageIO)
		}
	}
	return nil
}

func validScratchDirectory(name string) bool {
	if !strings.HasPrefix(name, scratchDirectoryPrefix) {
		return false
	}
	suffix := name[len(scratchDirectoryPrefix):]
	if suffix == "" || len(suffix) > 10 {
		return false
	}
	for _, value := range suffix {
		if value < '0' || value > '9' {
			return false
		}
	}
	return true
}
