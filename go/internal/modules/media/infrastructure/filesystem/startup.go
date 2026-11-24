package filesystem

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/prune"
)

// CleanStartup 仅在持有数据库实例锁且后台任务尚未启动时调用。
func CleanStartup(ctx context.Context, path string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	path, err := filepath.Abs(path)
	if err != nil {
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
	parent, err := root.Lstat("staging")
	if errors.Is(err, os.ErrNotExist) {
		return ctx.Err()
	}
	if err != nil || !parent.IsDir() || parent.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("%w: staging 根目录无效", mediadomain.ErrImageIO)
	}
	if err = cleanStartupWorkspaces(ctx, root, "staging/lq", validTaskDirectory, "LQ"); err != nil {
		return err
	}
	if err = cleanStartupWorkspaces(ctx, root, "staging/covers", validCoverStageDirectory, "封面"); err != nil {
		return err
	}
	for _, name := range []string{"staging/metadata", "staging/transcode", "staging/hq-release"} {
		cleanEmptyTaskWorkspaces(ctx, root, name)
	}
	return nil
}

// cleanEmptyTaskWorkspaces 只删除空的任务级暂存目录。非空目录可能保存可恢复检查点
// （侧车旧临时文件、HQ 隔离件、转码产物），必须保留；未知对象同样保留。
// 这些目录只是可重建的空壳，清理失败或超时不阻止启动。
func cleanEmptyTaskWorkspaces(ctx context.Context, root *os.Root, path string) {
	info, err := root.Lstat(path)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return
	}
	stage, err := root.OpenRoot(path)
	if err != nil {
		return
	}
	defer stage.Close()
	directory, err := stage.Open(".")
	if err != nil {
		return
	}
	defer directory.Close()
	count := 0
	for {
		if ctx.Err() != nil {
			return
		}
		batch, readErr := directory.ReadDir(128)
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			return
		}
		for _, entry := range batch {
			count++
			if count > 4096 {
				return
			}
			// 绑定挂载上 readdir 的类型可能是未知值，不能用 entry.IsDir() 判断。
			info, statErr := stage.Lstat(entry.Name())
			if statErr != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 || !validTaskDirectory(entry.Name()) {
				continue
			}
			prune.EmptyDirectories(stage, entry.Name())
		}
		if errors.Is(readErr, io.EOF) {
			return
		}
	}
}

func cleanStartupWorkspaces(ctx context.Context, root *os.Root, path string, validName func(string) bool, label string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	info, err := root.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("%w: %s 暂存根目录无效", mediadomain.ErrImageIO, label)
	}
	stage, err := root.OpenRoot(path)
	if err != nil {
		return fmt.Errorf("%w: 打开 %s 暂存根目录失败", mediadomain.ErrImageIO, label)
	}
	defer stage.Close()
	directory, err := stage.Open(".")
	if err != nil {
		return fmt.Errorf("%w: 读取 %s 暂存根目录失败", mediadomain.ErrImageIO, label)
	}
	names := make([]string, 0, 16)
	for {
		if err = ctx.Err(); err != nil {
			directory.Close()
			return err
		}
		batch, readErr := directory.ReadDir(128)
		if readErr != nil && readErr != io.EOF {
			directory.Close()
			return fmt.Errorf("%w: 读取 %s 暂存目录项失败", mediadomain.ErrImageIO, label)
		}
		for _, entry := range batch {
			if len(names) >= 4096 || !validName(entry.Name()) {
				directory.Close()
				return fmt.Errorf("%w: 存在未知 %s 暂存对象", mediadomain.ErrImageIO, label)
			}
			info, statErr := stage.Lstat(entry.Name())
			if statErr != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
				directory.Close()
				return fmt.Errorf("%w: %s 暂存工作区类型无效", mediadomain.ErrImageIO, label)
			}
			names = append(names, entry.Name())
		}
		if readErr == io.EOF {
			break
		}
	}
	if err = directory.Close(); err != nil {
		return fmt.Errorf("%w: 关闭 %s 暂存目录失败", mediadomain.ErrImageIO, label)
	}
	for _, name := range names {
		if err = ctx.Err(); err != nil {
			return err
		}
		if err = stage.RemoveAll(name); err != nil {
			return fmt.Errorf("%w: 删除 %s 暂存工作区失败", mediadomain.ErrImageIO, label)
		}
	}
	return nil
}

func validTaskDirectory(name string) bool {
	if len(name) != len("task_")+32 || name[:len("task_")] != "task_" {
		return false
	}
	value, err := hex.DecodeString(name[len("task_"):])
	return err == nil && len(value) == 16
}

func validCoverStageDirectory(name string) bool {
	for _, prefix := range []string{"cover-", "frame-"} {
		if len(name) <= len(prefix) || len(name)-len(prefix) > 10 || name[:len(prefix)] != prefix {
			continue
		}
		digits := true
		for _, value := range name[len(prefix):] {
			if value < '0' || value > '9' {
				digits = false
				break
			}
		}
		if digits {
			return true
		}
	}
	return false
}
