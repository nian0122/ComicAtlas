package filesystem

import (
	"bytes"
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"comicatlas.local/backend/internal/platform/archive"
	"comicatlas.local/backend/internal/platform/relocate"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type Files struct {
	root, stage, exports *os.Root
	directoryMutex       sync.Mutex
}

var _ exportports.Files = (*Files)(nil)

// New 打开媒体根和导出根。导出根可以位于媒体根之外（例如持久卷的 exports 兄弟目录），
// 但两者必须位于同一文件系统，发布才能沿用重命名而不复制。
func New(mediaDirectory, exportDirectory string) (*Files, error) {
	if err := os.MkdirAll(mediaDirectory, 0750); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(exportDirectory, 0750); err != nil {
		return nil, err
	}
	root, err := os.OpenRoot(mediaDirectory)
	if err != nil {
		return nil, err
	}
	exports, err := os.OpenRoot(exportDirectory)
	if err != nil {
		root.Close()
		return nil, err
	}
	files := &Files{root: root, exports: exports}
	for _, name := range []string{"staging/exports", "exports"} {
		if err = files.rootFor(name).MkdirAll(name, 0750); err != nil {
			files.Close()
			return nil, err
		}
		if err = files.safeParents(name + "/placeholder"); err != nil {
			files.Close()
			return nil, err
		}
	}
	files.stage, err = root.OpenRoot("staging/exports")
	if err != nil {
		files.Close()
		return nil, err
	}
	stageDirectory, err := root.Open("staging/exports")
	if err != nil {
		files.Close()
		return nil, err
	}
	defer stageDirectory.Close()
	exportDirectoryHandle, err := exports.Open("exports")
	if err != nil {
		files.Close()
		return nil, err
	}
	defer exportDirectoryHandle.Close()
	if err = sameDirectoryVolume(stageDirectory, exportDirectoryHandle); err != nil {
		files.Close()
		return nil, err
	}
	for _, name := range []string{"staging/exports", "staging", "."} {
		if err = files.syncDirectory(name); err != nil {
			files.Close()
			return nil, err
		}
	}
	if err = syncDirectoryOn(files.exports, "."); err != nil {
		files.Close()
		return nil, err
	}
	return files, nil
}
func (files *Files) Close() error {
	return errors.Join(files.stage.Close(), files.root.Close(), files.exports.Close())
}

// rootFor 把导出产物路径路由到导出根，其余来源与暂存路径留在媒体根。
func (files *Files) rootFor(name string) *os.Root {
	if name == "exports" || strings.HasPrefix(name, "exports/") {
		return files.exports
	}
	return files.root
}
func (files *Files) safeParents(name string) error {
	if !filepath.IsLocal(filepath.FromSlash(name)) || path.Clean(name) != name || strings.ContainsAny(name, "\\:") {
		return exportdomain.ErrSource
	}
	parts := strings.Split(name, "/")
	for index := 1; index < len(parts); index++ {
		info, err := files.rootFor(name).Lstat(strings.Join(parts[:index], "/"))
		if err != nil {
			return err
		}
		if !info.IsDir() {
			return exportdomain.ErrSource
		}
	}
	return nil
}
func (files *Files) openSource(ctx context.Context, source exportdomain.Source) (*os.File, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := files.safeParents(source.Path); err != nil {
		return nil, exportdomain.ErrSource
	}
	before, err := files.root.Lstat(source.Path)
	if err != nil || !before.Mode().IsRegular() || before.Size() != source.Size {
		return nil, exportdomain.ErrSource
	}
	file, err := files.root.Open(source.Path)
	if err != nil {
		return nil, exportdomain.ErrSource
	}
	after, err := file.Stat()
	if err != nil || !after.Mode().IsRegular() || !os.SameFile(before, after) || after.Size() != source.Size {
		file.Close()
		return nil, exportdomain.ErrSource
	}
	return file, nil
}

type countedReader struct {
	io.ReadCloser
	done func() error
}

func (reader countedReader) Close() error {
	err := reader.ReadCloser.Close()
	if err != nil {
		return err
	}
	return reader.done()
}
func (files *Files) Build(ctx context.Context, id string, snapshot exportdomain.Snapshot, progress func(int) error) ([]exportdomain.Artifact, error) {
	if !exportdomain.TaskID(id) {
		return nil, exportdomain.ErrValidation
	}
	if err := exportdomain.ValidateSnapshot(snapshot); err != nil {
		return nil, err
	}
	plan, err := exportdomain.Entries(snapshot)
	if err != nil {
		return nil, err
	}
	entries := make([]archive.WriteEntry, 0, len(plan))
	completed := 0
	wrap := func(reader io.ReadCloser) io.ReadCloser {
		return countedReader{reader, func() error { completed++; return progress(completed) }}
	}
	for _, item := range plan {
		entry := archive.WriteEntry{Name: item.Name, Directory: item.Directory}
		if item.Source != nil {
			source := *item.Source
			entry.Size = source.Size
			entry.Open = func(ctx context.Context) (io.ReadCloser, error) {
				reader, err := files.openSource(ctx, source)
				if err != nil {
					return nil, err
				}
				return wrap(reader), nil
			}
		} else if !item.Directory {
			data := item.Data
			entry.Size = int64(len(data))
			entry.Open = func(context.Context) (io.ReadCloser, error) { return wrap(io.NopCloser(bytes.NewReader(data))), nil }
		}
		entries = append(entries, entry)
	}
	artifacts, err := archive.Write(ctx, files.stage, id, archive.WriteOptions{Name: exportdomain.ArchiveName(snapshot), PartBytes: snapshot.Request.PartBytes}, entries)
	if errors.Is(err, archive.ErrSourceChanged) {
		return nil, exportdomain.ErrSource
	}
	if err != nil {
		return nil, err
	}
	if err = files.syncDirectory("staging/exports/" + id); err != nil {
		return nil, err
	}
	if err = files.syncDirectory("staging/exports"); err != nil {
		return nil, err
	}
	result := make([]exportdomain.Artifact, 0, len(artifacts))
	for _, artifact := range artifacts {
		result = append(result, exportdomain.Artifact{FileName: artifact.FileName, Size: artifact.Size, Modified: artifact.Modified, LastSegment: artifact.LastSegment})
	}
	return result, nil
}

func (files *Files) Cleanup(ctx context.Context, id string) error {
	_, err := files.cleanup(ctx, id)
	return err
}

func (files *Files) cleanup(ctx context.Context, id string) (bool, error) {
	if !exportdomain.TaskID(id) {
		return false, exportdomain.ErrValidation
	}
	if err := files.lockCleanup(ctx); err != nil {
		return false, err
	}
	defer files.directoryMutex.Unlock()
	if err := ctx.Err(); err != nil {
		return false, err
	}
	// 目录移出的暂存区可能已经保存唯一原件，不能走归档取消的递归清理。
	marker, err := files.directoryEntry("staging/exports/" + id + "/" + exportdomain.DirectoryMarkerName)
	if err != nil {
		return false, err
	}
	if marker != nil {
		return false, exportdomain.ErrConflict
	}
	info, err := files.stage.Lstat(id)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if !info.IsDir() {
		return false, exportdomain.ErrStorage
	}
	err = files.stage.RemoveAll(id)
	return err == nil, err
}

func (files *Files) lockCleanup(ctx context.Context) error {
	if files.directoryMutex.TryLock() {
		return nil
	}
	ticker := time.NewTicker(25 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			if files.directoryMutex.TryLock() {
				return nil
			}
		}
	}
}

func (files *Files) CleanupCancelled(ctx context.Context, cancelled func(context.Context, string) (bool, error)) (int, error) {
	directory, err := files.stage.Open(".")
	if err != nil {
		return 0, exportdomain.ErrStorage
	}
	defer directory.Close()
	var firstFailure error
	scanned, cleaned, failed := 0, 0, 0
	for {
		if err = ctx.Err(); err != nil {
			return cleaned, errors.Join(firstFailure, err)
		}
		entries, readErr := directory.ReadDir(128)
		for _, entry := range entries {
			scanned++
			if scanned > 100000 {
				return cleaned, errors.Join(firstFailure, exportdomain.ErrStorage)
			}
			if !entry.IsDir() || !exportdomain.TaskID(entry.Name()) {
				continue
			}
			remove, err := cancelled(ctx, entry.Name())
			if err != nil {
				return cleaned, errors.Join(firstFailure, err)
			}
			if !remove {
				continue
			}
			removed, err := files.cleanup(ctx, entry.Name())
			if err != nil {
				failed++
				if firstFailure == nil {
					firstFailure = err
				}
				if ctx.Err() != nil {
					return cleaned, errors.Join(firstFailure, ctx.Err())
				}
			} else if removed {
				cleaned++
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return cleaned, errors.Join(firstFailure, exportdomain.ErrStorage)
		}
	}
	if firstFailure != nil {
		return cleaned, fmt.Errorf("%d 个导出暂存目录待重试: %w", failed, firstFailure)
	}
	return cleaned, nil
}
func (files *Files) Publish(ctx context.Context, id string, artifacts []exportdomain.Artifact) error {
	if !exportdomain.TaskID(id) {
		return exportdomain.ErrValidation
	}
	target, stage := "exports/"+id, "staging/exports/"+id
	if _, err := files.rootFor(target).Lstat(target); err == nil {
		if err = files.verify(ctx, target, artifacts); err != nil {
			return err
		}
		if err = files.Cleanup(ctx, id); err != nil {
			return err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	} else {
		if err = files.verify(ctx, stage, artifacts); err != nil {
			return err
		}
		if err = ctx.Err(); err != nil {
			return err
		}
		if err = files.publish(ctx, stage, target); err != nil {
			return err
		}
	}
	for _, name := range []string{target, "exports", "staging/exports"} {
		if err := files.syncDirectory(name); err != nil {
			return err
		}
	}
	return nil
}

// publish 在媒体根暂存区和导出根之间做同卷、不覆盖的重命名，不做复制降级。
func (files *Files) publish(ctx context.Context, stage, target string) error {
	info, err := files.root.Lstat(stage)
	if err != nil {
		return err
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	from, err := files.root.Open("staging/exports")
	if err != nil {
		return err
	}
	defer from.Close()
	to, err := files.exports.Open("exports")
	if err != nil {
		return err
	}
	defer to.Close()
	if err = sameDirectoryVolume(from, to); err != nil {
		return err
	}
	return relocate.NoReplace(from, to, path.Base(stage), path.Base(target), info)
}
func (files *Files) verify(ctx context.Context, directory string, artifacts []exportdomain.Artifact) error {
	if err := files.safeParents(directory + "/placeholder"); err != nil {
		return err
	}
	root, err := files.rootFor(directory).OpenRoot(directory)
	if err != nil {
		return err
	}
	defer root.Close()
	folder, err := root.Open(".")
	if err != nil {
		return err
	}
	names, err := folder.ReadDir(130)
	folder.Close()
	if err != nil && !errors.Is(err, io.EOF) {
		return err
	}
	if len(names) != len(artifacts) {
		return exportdomain.ErrManifest
	}
	for _, artifact := range artifacts {
		if artifact.FileName == "" || artifact.FileName == "." || path.Base(artifact.FileName) != artifact.FileName || strings.ContainsAny(artifact.FileName, "\\:") {
			return exportdomain.ErrManifest
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		current, err := root.Lstat(artifact.FileName)
		if err != nil {
			return err
		}
		if !current.Mode().IsRegular() || current.Size() != artifact.Size || current.ModTime().UnixNano() != artifact.Modified {
			return exportdomain.ErrManifest
		}
	}
	return nil
}
