package filesystem

import (
	"context"
	"errors"
	"os"
	"path"
	"path/filepath"

	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/relocate"
)

// publishFile 用同盘无覆盖重命名发布，不要求媒体文件系统支持硬链接。
// 重命名后的失败保留目标，调用方重试仍须核验已存在的输出。
func (storage *Storage) publishFile(ctx context.Context, source, target string, verify func(string) error) error {
	if err := storage.existingParents(source); err != nil {
		return err
	}
	if err := storage.existingParents(target); err != nil {
		return err
	}
	if err := storage.root.Link(filepath.FromSlash(source), filepath.FromSlash(target)); err == nil {
		return storage.finishPublishedFile(ctx, source, target, verify)
	} else if !relocate.LinkUnsupported(err) {
		return err
	}
	if err := verify(source); err != nil {
		return err
	}
	before, err := storage.root.Lstat(filepath.FromSlash(source))
	if err != nil || !before.Mode().IsRegular() {
		return errors.Join(mediadomain.ErrImageChanged, err)
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := relocate.WithinRoot(storage.root, source, target, before); err != nil {
		return err
	}
	if err := verify(target); err != nil {
		return err
	}
	if err := storage.syncPublishedFile(target); err != nil {
		return err
	}
	return storage.syncDirectory(path.Dir(source))
}

func (storage *Storage) finishPublishedFile(ctx context.Context, source, target string, verify func(string) error) error {
	if err := verify(target); err != nil {
		return err
	}
	original, err := storage.root.Lstat(filepath.FromSlash(source))
	if err != nil {
		return err
	}
	published, err := storage.root.Lstat(filepath.FromSlash(target))
	if err != nil {
		return err
	}
	if !original.Mode().IsRegular() || !published.Mode().IsRegular() || !os.SameFile(original, published) {
		return mediadomain.ErrImageChanged
	}
	if err = storage.syncPublishedFile(target); err != nil {
		return err
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	if err = storage.existingParents(source); err != nil {
		return err
	}
	current, err := storage.root.Lstat(filepath.FromSlash(source))
	if err != nil {
		return err
	}
	if !current.Mode().IsRegular() || !os.SameFile(current, published) || current.Size() != published.Size() || !current.ModTime().Equal(published.ModTime()) {
		return mediadomain.ErrImageChanged
	}
	destination, err := storage.root.Lstat(filepath.FromSlash(target))
	if err != nil {
		return err
	}
	if !destination.Mode().IsRegular() || !os.SameFile(current, destination) {
		return mediadomain.ErrImageChanged
	}
	if err = storage.root.Remove(filepath.FromSlash(source)); err != nil {
		return err
	}
	return storage.syncDirectory(path.Dir(source))
}

func (storage *Storage) syncPublishedFile(name string) error {
	if err := storage.existingParents(name); err != nil {
		return err
	}
	before, err := storage.root.Lstat(filepath.FromSlash(name))
	if err != nil {
		return err
	}
	file, err := storage.root.OpenFile(filepath.FromSlash(name), os.O_RDWR, 0)
	if err != nil {
		return err
	}
	opened, statErr := file.Stat()
	if statErr != nil || !before.Mode().IsRegular() || !os.SameFile(before, opened) {
		file.Close()
		return mediadomain.ErrImageChanged
	}
	if err = errors.Join(file.Sync(), file.Close()); err != nil {
		return err
	}
	current, err := storage.root.Lstat(filepath.FromSlash(name))
	if err != nil {
		return err
	}
	if !current.Mode().IsRegular() || !os.SameFile(current, before) || current.Size() != before.Size() || !current.ModTime().Equal(before.ModTime()) {
		return mediadomain.ErrImageChanged
	}
	return storage.syncParents(name)
}
