package filesystem

import (
	"context"
	"io"
	"os"
	"path"
	"path/filepath"

	"comicatlas.local/backend/internal/modules/backup/domain"
)

// 每层仅保留一批目录项，清单排序由磁盘索引负责。
func walk(ctx context.Context, root *os.Root, name string, depth int, consume func(string, os.FileInfo) error) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if depth >= domain.MaxDepth {
		return domain.ErrSource
	}
	file, err := root.Open(filepath.FromSlash(name))
	if err != nil {
		return domain.ErrSource
	}
	defer file.Close()
	for {
		batch, err := file.ReadDir(128)
		if err != nil && err != io.EOF {
			return domain.ErrSource
		}
		for _, item := range batch {
			if err := ctx.Err(); err != nil {
				return err
			}
			next := path.Join(name, item.Name())
			info, err := regularPath(root, next)
			if err != nil {
				return err
			}
			if err := consume(next, info); err != nil {
				return err
			}
			if info.IsDir() {
				if err := walk(ctx, root, next, depth+1, consume); err != nil {
					return err
				}
			}
		}
		if err == io.EOF {
			return nil
		}
	}
}

func syncTree(ctx context.Context, root *os.Root, name string, depth int) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if depth >= domain.MaxDepth {
		return domain.ErrSource
	}
	file, err := root.Open(filepath.FromSlash(name))
	if err != nil {
		return domain.ErrIO
	}
	defer file.Close()
	for {
		batch, err := file.ReadDir(128)
		if err != nil && err != io.EOF {
			return domain.ErrIO
		}
		for _, item := range batch {
			if item.IsDir() {
				if err := syncTree(ctx, root, path.Join(name, item.Name()), depth+1); err != nil {
					return err
				}
			}
		}
		if err == io.EOF {
			if err := syncDirectory(root, filepath.FromSlash(name)); err != nil {
				return domain.ErrIO
			}
			return nil
		}
	}
}
