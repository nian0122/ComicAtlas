package filesystem

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"errors"
	"io"
	"os"
	"strings"
)

type Files struct {
	root    *os.Root
	observe func(string, os.FileInfo) error
}

func New(path string) (*Files, error) {
	root, err := os.OpenRoot(path)
	if err != nil {
		return nil, err
	}
	return &Files{root: root}, nil
}
func (files *Files) Close() error { return files.root.Close() }

func (files *Files) directory(name string) (*os.File, error) {
	before, err := files.root.Lstat(name)
	if errors.Is(err, os.ErrNotExist) {
		if files.observe != nil {
			if recordErr := files.observe(name, nil); recordErr != nil {
				return nil, recordErr
			}
		}
		return nil, recoverydomain.ErrMissing
	}
	if err != nil {
		return nil, err
	}
	if !before.IsDir() || before.Mode()&os.ModeSymlink != 0 {
		return nil, recoverydomain.ErrUnsafe
	}
	opened, err := files.root.Open(name)
	if err != nil {
		return nil, err
	}
	info, err := opened.Stat()
	if err != nil || !os.SameFile(before, info) {
		opened.Close()
		return nil, recoverydomain.ErrChanged
	}
	if files.observe != nil {
		if err = files.observe(name, before); err != nil {
			opened.Close()
			return nil, err
		}
	}
	return opened, nil
}

// ScanHQ 只枚举配置媒体根的直接漫画目录，不进入 exports/trash/retired，也不写文件。
func (files *Files) ScanHQ(ctx context.Context, yield func(string) error) (result recoverydomain.Discovery, err error) {
	if yield == nil {
		return result, recoverydomain.ErrValidation
	}
	if err = ctx.Err(); err != nil {
		return result, err
	}
	directory, err := files.directory("hq")
	if err != nil {
		return result, err
	}
	defer func() { err = errors.Join(err, directory.Close()) }()
	before, err := directory.Stat()
	if err != nil {
		return result, err
	}
	entries := 0
	for {
		items, readErr := directory.ReadDir(128)
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			return result, readErr
		}
		for _, item := range items {
			if err = ctx.Err(); err != nil {
				return result, err
			}
			entries++
			if entries > recoverydomain.MaxRootEntries {
				return result, recoverydomain.ErrLimit
			}
			if !recoverydomain.Key(item.Name()) || !item.IsDir() {
				result.Ignored++
				continue
			}
			if result.Candidates >= recoverydomain.MaxCandidates {
				return result, recoverydomain.ErrLimit
			}
			// 不信任可能过时的 DirEntry；实际目录身份在后续单本读取时再次验证。
			info, statErr := files.root.Lstat("hq/" + item.Name())
			if statErr != nil {
				return result, statErr
			}
			if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
				return result, recoverydomain.ErrUnsafe
			}
			if err = yield(item.Name()); err != nil {
				return result, err
			}
			result.Candidates++
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
	}
	after, err := directory.Stat()
	if err != nil {
		return result, err
	}
	current, err := files.root.Lstat("hq")
	if err != nil {
		return result, err
	}
	if !os.SameFile(before, current) || !before.ModTime().Equal(after.ModTime()) {
		return result, recoverydomain.ErrChanged
	}
	return result, nil
}

func (files *Files) parents(name string) error {
	parts := strings.Split(name, "/")
	current := ""
	for _, part := range parts[:len(parts)-1] {
		if current != "" {
			current += "/"
		}
		current += part
		directory, err := files.directory(current)
		if err != nil {
			return err
		}
		if err = directory.Close(); err != nil {
			return err
		}
	}
	return nil
}
