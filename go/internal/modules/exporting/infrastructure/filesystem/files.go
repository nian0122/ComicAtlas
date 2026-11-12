package filesystem

import (
	"bytes"
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"comicatlas.local/backend/internal/platform/archive"
	"context"
	"crypto/sha256"
	"encoding/hex"
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
	root, stage     *os.Root
	directoryMutex  sync.Mutex
	archiveMutex    sync.Mutex
	archiveTask     string
	archiveVerified map[string]os.FileInfo
}

var _ exportports.Files = (*Files)(nil)

func New(directory string) (*Files, error) {
	if err := os.MkdirAll(directory, 0750); err != nil {
		return nil, err
	}
	root, err := os.OpenRoot(directory)
	if err != nil {
		return nil, err
	}
	files := &Files{root: root}
	for _, name := range []string{"staging/exports", "exports"} {
		if err = root.MkdirAll(name, 0750); err != nil {
			root.Close()
			return nil, err
		}
		if err = files.safeParents(name + "/placeholder"); err != nil {
			root.Close()
			return nil, err
		}
	}
	files.stage, err = root.OpenRoot("staging/exports")
	if err != nil {
		root.Close()
		return nil, err
	}
	for _, name := range []string{"staging/exports", "staging", "exports", "."} {
		if err = files.syncDirectory(name); err != nil {
			files.Close()
			return nil, err
		}
	}
	return files, nil
}
func (files *Files) Close() error { return errors.Join(files.stage.Close(), files.root.Close()) }
func (files *Files) safeParents(name string) error {
	if !filepath.IsLocal(filepath.FromSlash(name)) || path.Clean(name) != name || strings.ContainsAny(name, "\\:") {
		return exportdomain.ErrSource
	}
	parts := strings.Split(name, "/")
	for index := 1; index < len(parts); index++ {
		info, err := files.root.Lstat(strings.Join(parts[:index], "/"))
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
		if item.Directory {
			sum := sha256.Sum256(nil)
			entry.SHA256 = hex.EncodeToString(sum[:])
		} else if item.Source != nil {
			source := *item.Source
			entry.Size, entry.SHA256 = source.Size, source.SHA256
			entry.Open = func(ctx context.Context) (io.ReadCloser, error) {
				reader, err := files.openSource(ctx, source)
				if err != nil {
					return nil, err
				}
				return wrap(reader), nil
			}
		} else {
			data := item.Data
			sum := sha256.Sum256(data)
			entry.Size, entry.SHA256 = int64(len(data)), hex.EncodeToString(sum[:])
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
	receipts := map[string]os.FileInfo{}
	for _, artifact := range artifacts {
		result = append(result, exportdomain.Artifact{FileName: artifact.FileName, Size: artifact.Size, SHA256: artifact.SHA256, LastSegment: artifact.LastSegment})
		receipts[artifact.FileName+"\x00"+artifact.SHA256] = artifact.VerifiedInfo.Info
	}
	files.archiveMutex.Lock()
	files.archiveTask, files.archiveVerified = id, receipts
	files.archiveMutex.Unlock()
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
	if _, err := files.root.Lstat(target); err == nil {
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
		if err = files.root.Rename(stage, target); err != nil {
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
func (files *Files) verify(ctx context.Context, directory string, artifacts []exportdomain.Artifact) error {
	if err := files.safeParents(directory + "/placeholder"); err != nil {
		return err
	}
	root, err := files.root.OpenRoot(directory)
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
	buffer := make([]byte, 256<<10)
	for _, artifact := range artifacts {
		if artifact.FileName == "" || artifact.FileName == "." || path.Base(artifact.FileName) != artifact.FileName || strings.ContainsAny(artifact.FileName, "\\:") {
			return exportdomain.ErrManifest
		}
		files.archiveMutex.Lock()
		var receipt os.FileInfo
		if files.archiveTask == path.Base(directory) {
			receipt = files.archiveVerified[artifact.FileName+"\x00"+artifact.SHA256]
		}
		files.archiveMutex.Unlock()
		current, statErr := root.Lstat(artifact.FileName)
		if statErr != nil {
			return statErr
		}
		if receipt != nil && current.Mode().IsRegular() && os.SameFile(receipt, current) && current.Size() == artifact.Size && current.ModTime().Equal(receipt.ModTime()) {
			continue
		}
		if err = verifyArtifact(ctx, root, artifact, buffer); err != nil {
			return err
		}
	}
	return nil
}
func verifyArtifact(ctx context.Context, root *os.Root, artifact exportdomain.Artifact, buffer []byte) (err error) {
	before, err := root.Lstat(artifact.FileName)
	if err != nil {
		return err
	}
	if !before.Mode().IsRegular() || before.Size() != artifact.Size {
		return exportdomain.ErrManifest
	}
	file, err := root.Open(artifact.FileName)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, file.Close()) }()
	opened, err := file.Stat()
	if err != nil {
		return err
	}
	if !os.SameFile(opened, before) || opened.Size() != artifact.Size {
		return exportdomain.ErrManifest
	}
	hash := sha256.New()
	var count int64
	for {
		if err = ctx.Err(); err != nil {
			return err
		}
		n, readErr := file.Read(buffer)
		if n > 0 {
			count += int64(n)
			if count > artifact.Size {
				return exportdomain.ErrManifest
			}
			hash.Write(buffer[:n])
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return readErr
		}
	}
	if count != artifact.Size || hex.EncodeToString(hash.Sum(nil)) != artifact.SHA256 {
		return exportdomain.ErrManifest
	}
	after, err := file.Stat()
	if err != nil {
		return err
	}
	if after.Size() != opened.Size() || !after.ModTime().Equal(opened.ModTime()) {
		return exportdomain.ErrManifest
	}
	return nil
}
