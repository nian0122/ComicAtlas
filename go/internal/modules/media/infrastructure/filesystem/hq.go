package filesystem

import (
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/prune"
	"context"
	"errors"
	"os"
	"path"
	"path/filepath"
)

var _ mediaports.HQStorage = (*Storage)(nil)

func (storage *Storage) VerifyHQ(ctx context.Context, item mediadomain.HQReleaseItem) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if !mediadomain.ValidHQPath(item.SourcePath) || !filepath.IsLocal(item.SourcePath) {
		return mediadomain.ErrImageOutput
	}
	if err := storage.existingParents(item.SourcePath); err != nil {
		return err
	}
	return storage.verifyFileAttributes(ctx, item.SourcePath, item.OriginalBytes, item.SourceModified)
}

func (storage *Storage) verifyFileAttributes(ctx context.Context, name string, size, modified int64) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	info, err := storage.root.Lstat(filepath.FromSlash(name))
	if errors.Is(err, os.ErrNotExist) {
		return mediadomain.ErrLQNotFound
	}
	if err != nil {
		return mediadomain.ErrImageIO
	}
	if !info.Mode().IsRegular() || info.Size() != size || modified != 0 && info.ModTime().UnixNano() != modified {
		return mediadomain.ErrImageChanged
	}
	return nil
}
func (storage *Storage) ReleaseHQ(ctx context.Context, taskID string, item mediadomain.HQReleaseItem) (string, error) {
	if !safeKey(taskID) || !mediadomain.ValidHQPath(item.SourcePath) || !filepath.IsLocal(item.SourcePath) {
		return "", mediadomain.ErrImageOutput
	}
	if err := storage.existingParents(item.SourcePath); err != nil {
		return "", err
	}
	valid, err := storage.Verify(ctx, item.LQ)
	if err != nil {
		return "", err
	}
	if !valid {
		return "", mediadomain.ErrHQPrerequisite
	}
	if err = storage.syncRetainedLQ(item.LQ); err != nil {
		return "", mediadomain.ErrImageIO
	}
	parent := "staging/hq-release/" + taskID
	if err = storage.directory(parent, 0700); err != nil {
		return "", err
	}
	defer prune.EmptyDirectories(storage.root, parent)
	quarantine := parent + "/" + item.MediaID + ".hq"
	_, originalErr := storage.root.Lstat(filepath.FromSlash(item.SourcePath))
	_, stagedErr := storage.root.Lstat(filepath.FromSlash(quarantine))
	if originalErr != nil && !errors.Is(originalErr, os.ErrNotExist) || stagedErr != nil && !errors.Is(stagedErr, os.ErrNotExist) {
		return "", mediadomain.ErrImageIO
	}
	if originalErr == nil && stagedErr == nil {
		original, err := storage.root.Lstat(filepath.FromSlash(item.SourcePath))
		if err != nil {
			return "", err
		}
		retained, err := storage.root.Lstat(filepath.FromSlash(quarantine))
		if err != nil {
			return "", err
		}
		if !original.Mode().IsRegular() || !retained.Mode().IsRegular() || !os.SameFile(original, retained) {
			return "", mediadomain.ErrLQConflict
		}
	}
	if errors.Is(originalErr, os.ErrNotExist) && errors.Is(stagedErr, os.ErrNotExist) {
		return "already_absent", nil
	}
	if originalErr == nil {
		if err = storage.VerifyHQ(ctx, item); err != nil {
			return "", err
		}
		if err = ctx.Err(); err != nil {
			return "", err
		}
		verify := func(name string) error {
			return storage.verifyFileAttributes(ctx, name, item.OriginalBytes, item.SourceModified)
		}
		if stagedErr == nil {
			err = storage.finishPublishedFile(ctx, item.SourcePath, quarantine, verify)
		} else {
			err = storage.publishFile(ctx, item.SourcePath, quarantine, verify)
		}
		if errors.Is(err, os.ErrExist) {
			return "", mediadomain.ErrLQConflict
		}
		if err != nil {
			return "", err
		}
	}
	// 核对隔离后的文件再删除，崩溃留下的暂存使用同一协议恢复。
	if err = storage.verifyFileAttributes(ctx, quarantine, item.OriginalBytes, item.SourceModified); err != nil {
		return "", err
	}
	if err = ctx.Err(); err != nil {
		return "", err
	}
	if err = storage.root.Remove(filepath.FromSlash(quarantine)); err != nil {
		return "", mediadomain.ErrImageIO
	}
	if err = storage.syncDirectory(parent); err != nil {
		return "", mediadomain.ErrImageIO
	}
	return "removed", nil
}

func (storage *Storage) syncRetainedLQ(record mediadomain.LQRecord) error {
	file, err := storage.root.OpenFile(filepath.FromSlash(record.Path), os.O_RDWR, 0)
	if err != nil {
		return err
	}
	syncErr := file.Sync()
	closeErr := file.Close()
	if err = errors.Join(syncErr, closeErr); err != nil {
		return err
	}
	for directory := path.Dir(record.Path); ; directory = path.Dir(directory) {
		if err = storage.syncDirectory(directory); err != nil {
			return err
		}
		if directory == "." {
			return nil
		}
	}
}
