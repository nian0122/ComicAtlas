package filesystem

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"

	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
)

// CleanStartup 仅在持有数据库实例锁、AI 线程尚未启动时调用，不用于在线清理。
func CleanStartup(ctx context.Context, path string) error {
	path, err := filepath.Abs(path)
	if err != nil {
		return aidomain.ErrPreparation
	}
	path, err = filepath.EvalSymlinks(path)
	if err != nil {
		return aidomain.ErrPreparation
	}
	root, err := os.OpenRoot(path)
	if err != nil {
		return aidomain.ErrPreparation
	}
	defer root.Close()
	for _, name := range []string{"staging", "staging/ai"} {
		info, err := root.Lstat(name)
		if errors.Is(err, os.ErrNotExist) {
			return ctx.Err()
		}
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return aidomain.ErrPreparation
		}
	}
	stage, err := root.OpenRoot("staging/ai")
	if err != nil {
		return aidomain.ErrPreparation
	}
	defer stage.Close()
	total := 0
	for {
		if err = ctx.Err(); err != nil {
			return err
		}
		entries, err := stage.Open(".")
		if err != nil {
			return aidomain.ErrPreparation
		}
		batch, readErr := entries.ReadDir(128)
		closeErr := entries.Close()
		if closeErr != nil {
			return aidomain.ErrPreparation
		}
		if readErr != nil && readErr != io.EOF {
			return aidomain.ErrPreparation
		}
		for _, entry := range batch {
			total++
			if total > 4096 || !stageIdentifier(entry.Name()) || !entry.IsDir() || entry.Type()&os.ModeSymlink != 0 {
				return aidomain.ErrPreparation
			}
			if err = ctx.Err(); err != nil {
				return err
			}
			if err = stage.RemoveAll(entry.Name()); err != nil {
				return aidomain.ErrPreparation
			}
		}
		if readErr == io.EOF {
			return nil
		}
	}
}

// 与 crypto/rand.Text 创建的工作区标识对应；未知对象保留并拒绝启用 AI。
func stageIdentifier(value string) bool {
	if len(value) < 26 || len(value) > 128 {
		return false
	}
	for _, character := range value {
		if (character < 'A' || character > 'Z') && (character < '2' || character > '7') {
			return false
		}
	}
	return true
}
