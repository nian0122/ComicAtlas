package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"comicatlas.local/backend/internal/platform/relocate"
	"context"
	"errors"
	"os"
	"path"
	"strings"
)

func (files *Files) directoryEntry(name string) (os.FileInfo, error) {
	if err := files.safeParents(name); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	info, err := files.root.Lstat(name)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	return info, err
}

// mkdirParents 在当前移出执行内缓存已确认或创建的目录，避免每个章节重复检查同一父路径。
func (move *directoryExecution) mkdirParents(name string) error {
	parts := strings.Split(name, "/")
	for index := 1; index < len(parts); index++ {
		directory := strings.Join(parts[:index], "/")
		if move.createdDirs[directory] {
			continue
		}
		info, err := move.entry(directory)
		if err != nil {
			return err
		}
		if info != nil {
			if !info.IsDir() {
				return exportdomain.ErrConflict
			}
			move.createdDirs[directory] = true
			continue
		}
		if err = move.files.root.Mkdir(directory, 0750); err != nil {
			return err
		}
		move.createdDirs[directory] = true
		if err = move.files.syncDirectory(directory); err != nil {
			return err
		}
		if err = move.files.syncDirectory(path.Dir(directory)); err != nil {
			return err
		}
	}
	return nil
}

func (move *directoryExecution) safeParent(name string) error {
	parent := path.Dir(name)
	if parent == "." || move.safeDirs[parent] {
		return nil
	}
	if err := move.files.safeParents(parent + "/placeholder"); err != nil {
		return err
	}
	move.safeDirs[parent] = true
	return nil
}

// entry 沿用目录级父路径缓存，仍对每个文件做独立 Lstat。
func (move *directoryExecution) entry(name string) (os.FileInfo, error) {
	if err := move.safeParent(name); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	info, err := move.files.root.Lstat(name)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	return info, err
}

func (move *directoryExecution) identity(name string) (string, error) {
	if err := move.safeParent(name); err != nil {
		return "", err
	}
	file, err := move.files.root.Open(name)
	if err != nil {
		return "", err
	}
	defer file.Close()
	return relocate.Identity(file)
}

func (move *directoryExecution) rename(ctx context.Context, source, target string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := move.safeParent(source); err != nil {
		return err
	}
	if err := move.safeParent(target); err != nil {
		return err
	}
	info, err := move.files.root.Lstat(source)
	if err != nil {
		return err
	}
	from, err := move.files.root.Open(path.Dir(source))
	if err != nil {
		return err
	}
	defer from.Close()
	to, err := move.files.root.Open(path.Dir(target))
	if err != nil {
		return err
	}
	defer to.Close()
	if err = sameDirectoryVolume(from, to); err != nil {
		return err
	}
	if err = relocate.NoReplace(from, to, path.Base(source), path.Base(target), info); err != nil {
		return err
	}
	move.stats.Renames++
	return nil
}

func (move *directoryExecution) syncParents(names map[string]bool) error {
	for name := range names {
		for {
			info, err := move.entry(name)
			if err != nil {
				return err
			}
			if info != nil {
				if !info.IsDir() {
					return exportdomain.ErrSource
				}
				if err = move.files.syncDirectory(name); err != nil {
					return err
				}
				break
			}
			if name == "." {
				return exportdomain.ErrSource
			}
			name = path.Dir(name)
		}
	}
	return nil
}
