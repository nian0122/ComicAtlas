package filesystem

import (
	"errors"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"

	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
)

// Files 在应用独占媒体根的前提下，串行执行本实例的清单及文件状态转换。
type Files struct {
	root   *os.Root
	mutex  sync.Mutex
	buffer [64 << 10]byte
}

func New(directory string) (*Files, error) {
	root, err := os.OpenRoot(directory)
	if err != nil {
		return nil, err
	}
	return &Files{root: root}, nil
}

func (files *Files) Close() error { return files.root.Close() }

// 对每个组件使用 Lstat；不接受原件、回收目录或中间目录中的符号链接。
func (files *Files) parents(name string, create bool) error {
	current := ""
	for _, part := range strings.Split(path.Dir(name), "/") {
		if current != "" {
			current += "/"
		}
		current += part
		info, err := files.root.Lstat(filepath.FromSlash(current))
		if errors.Is(err, os.ErrNotExist) && create {
			if err = files.root.Mkdir(filepath.FromSlash(current), 0700); err != nil {
				return err
			}
			if err = files.syncDirectory(path.Dir(current)); err != nil {
				return err
			}
			continue
		}
		if err != nil {
			return err
		}
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return lifecycledomain.ErrConflict
		}
	}
	return nil
}

func (files *Files) stat(name string) (os.FileInfo, error) {
	if err := files.parents(name, false); err != nil {
		return nil, err
	}
	info, err := files.root.Lstat(filepath.FromSlash(name))
	if err == nil && !info.Mode().IsRegular() {
		return nil, lifecycledomain.ErrConflict
	}
	return info, err
}
