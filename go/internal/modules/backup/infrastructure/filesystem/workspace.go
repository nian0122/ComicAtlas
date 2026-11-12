package filesystem

import (
	"context"
	"crypto/rand"
	"errors"
	"os"
	"path/filepath"
	"strings"

	"comicatlas.local/backend/internal/modules/backup/application/ports"
	"comicatlas.local/backend/internal/modules/backup/domain"
)

const stagePrefix = ".comicatlas-backup-"

type Filesystem struct{}

type workspace struct {
	root      *os.Root
	parent    *os.Root
	stage     string
	final     string
	stageInfo os.FileInfo
	sources   map[string]os.FileInfo
	published bool
	closed    bool
}

var _ ports.Filesystem = Filesystem{}

func (Filesystem) Begin(ctx context.Context, destination string, sourceNames []string) (ports.Workspace, []string, error) {
	if err := ctx.Err(); err != nil {
		return nil, nil, err
	}
	absolute, err := filepath.Abs(destination)
	if err != nil || !filepath.IsLocal(filepath.Base(absolute)) {
		return nil, nil, domain.ErrValidation
	}
	parentName, err := canonicalDirectory(filepath.Dir(absolute))
	if err != nil {
		return nil, nil, err
	}
	absolute = filepath.Join(parentName, filepath.Base(absolute))
	if _, err := os.Lstat(absolute); err == nil {
		return nil, nil, domain.ErrExists
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, nil, domain.ErrIO
	}
	sources := make(map[string]os.FileInfo, len(sourceNames))
	canonical := make([]string, 0, len(sourceNames))
	for _, name := range sourceNames {
		directory, err := canonicalDirectory(name)
		if err != nil {
			return nil, nil, err
		}
		contained, err := domain.ContainsDirectory(directory, absolute)
		if err != nil || contained {
			return nil, nil, domain.ErrValidation
		}
		info, err := os.Stat(directory)
		if err != nil {
			return nil, nil, domain.ErrSource
		}
		sources[directory] = info
		canonical = append(canonical, directory)
	}
	parent, err := os.OpenRoot(parentName)
	if err != nil {
		return nil, nil, domain.ErrIO
	}
	stage := stagePrefix + rand.Text()
	if err = parent.Mkdir(stage, 0o700); err != nil {
		parent.Close()
		return nil, nil, domain.ErrIO
	}
	info, err := parent.Lstat(stage)
	if err != nil {
		parent.Remove(stage)
		parent.Close()
		return nil, nil, domain.ErrIO
	}
	root, err := parent.OpenRoot(stage)
	if err != nil {
		parent.Remove(stage)
		parent.Close()
		return nil, nil, domain.ErrIO
	}
	return &workspace{root: root, parent: parent, stage: stage, final: filepath.Base(absolute), stageInfo: info, sources: sources}, canonical, nil
}

func canonicalDirectory(name string) (string, error) {
	absolute, err := filepath.Abs(name)
	if err != nil {
		return "", domain.ErrValidation
	}
	canonical, err := filepath.EvalSymlinks(absolute)
	if err != nil {
		return "", domain.ErrSource
	}
	info, err := os.Stat(canonical)
	if err != nil || !info.IsDir() {
		return "", domain.ErrSource
	}
	return canonical, nil
}

func (work *workspace) Root() string {
	if work.published {
		return filepath.Join(work.parent.Name(), work.final)
	}
	return filepath.Join(work.parent.Name(), work.stage)
}

func (work *workspace) Directory(ctx context.Context, name string) (domain.Entry, error) {
	entry := domain.Entry{Path: name, Kind: domain.Directory}
	if domain.ValidateEntry(entry) != nil || checkedPath(name, false) != nil || work.closed || work.root == nil {
		return entry, domain.ErrValidation
	}
	if err := ctx.Err(); err != nil {
		return entry, err
	}
	if err := work.root.Mkdir(filepath.FromSlash(name), 0o700); err != nil {
		return entry, domain.ErrIO
	}
	return entry, nil
}

func (work *workspace) Publish(ctx context.Context) error {
	if work.closed || work.published || work.root == nil {
		return domain.ErrInvalid
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := syncTree(ctx, work.root, ".", 0); err != nil {
		return err
	}
	if err := work.root.Close(); err != nil {
		return domain.ErrIO
	}
	work.root = nil
	if err := ctx.Err(); err != nil {
		return err
	}
	info, err := work.parent.Lstat(work.stage)
	if err != nil || !os.SameFile(info, work.stageInfo) || !info.IsDir() {
		return domain.ErrIO
	}
	if err := publishNoReplace(work.parent, work.stage, work.final); err != nil {
		if errors.Is(err, os.ErrExist) {
			return domain.ErrExists
		}
		if _, statErr := work.parent.Lstat(work.final); statErr == nil {
			return domain.ErrExists
		}
		return domain.ErrIO
	}
	work.published = true
	if err := syncDirectory(work.parent, "."); err != nil {
		return domain.ErrPublished
	}
	return nil
}

func (work *workspace) Close() (result error) {
	if work.closed {
		return nil
	}
	work.closed = true
	if work.root != nil {
		result = work.root.Close()
		work.root = nil
	}
	if !work.published {
		// 根句柄约束递归清理范围；只删除本次新建且文件身份未变的暂存目录。
		info, err := work.parent.Lstat(work.stage)
		if err == nil && strings.HasPrefix(work.stage, stagePrefix) && filepath.Base(work.stage) == work.stage && os.SameFile(info, work.stageInfo) {
			result = errors.Join(result, work.parent.RemoveAll(work.stage))
		} else if !errors.Is(err, os.ErrNotExist) {
			result = errors.Join(result, domain.ErrIO)
		}
	}
	result = errors.Join(result, work.parent.Close())
	if result != nil {
		return domain.ErrIO
	}
	return nil
}

func checkedPath(name string, metadata bool) error {
	if metadata && (name == domain.CatalogPath || name == domain.ManifestPath || name == domain.ReceiptPath) {
		return nil
	}
	if !domain.ValidPath(name) {
		return domain.ErrInvalid
	}
	native := filepath.FromSlash(name)
	if !filepath.IsLocal(native) || filepath.ToSlash(filepath.Clean(native)) != name {
		return domain.ErrInvalid
	}
	return nil
}

func regularPath(root *os.Root, name string) (os.FileInfo, error) {
	parts := strings.Split(name, "/")
	for index := range parts {
		info, err := root.Lstat(filepath.FromSlash(strings.Join(parts[:index+1], "/")))
		if err != nil || info.Mode()&os.ModeSymlink != 0 || !info.IsDir() && !info.Mode().IsRegular() || index < len(parts)-1 && !info.IsDir() {
			return nil, domain.ErrSource
		}
		if index == len(parts)-1 {
			return info, nil
		}
	}
	return nil, domain.ErrSource
}
