package filesystem

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path"
	"path/filepath"
	"strings"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

type VideoStorage struct {
	files   *Storage
	encoder mediaports.VideoEncoder
	probe   mediaports.Probe
}

func NewVideo(files *Storage, encoder mediaports.VideoEncoder, probe mediaports.Probe) *VideoStorage {
	return &VideoStorage{files: files, encoder: encoder, probe: probe}
}

var _ mediaports.TranscodeStorage = (*VideoStorage)(nil)

func videoParent(id string, item mediadomain.TranscodeItem) (string, error) {
	if !safeKey(id) || item.MediaID == "" || !mediadomain.ValidHQPath(item.SourcePath) || !filepath.IsLocal(item.SourcePath) {
		return "", mediadomain.ErrTranscodeInvalid
	}
	hash := sha256.Sum256([]byte(item.MediaID))
	return "staging/transcode/" + id + "/" + hex.EncodeToString(hash[:]), nil
}

// videoTarget 优先用源摘要命名；历史媒体没有源摘要时退回产物摘要。
func videoTarget(item mediadomain.TranscodeItem, record mediadomain.TranscodeRecord) (string, error) {
	revision := item.SourceSHA256
	if revision == "" {
		revision = record.SHA256
	}
	target := mediadomain.DerivedPath("hq", item.SourcePath, item.MediaID, revision, "mp4")
	if target == "" {
		return "", mediadomain.ErrTranscodeInvalid
	}
	return target, nil
}

func (storage *VideoStorage) verifySourceSize(item mediadomain.TranscodeItem) error {
	if err := storage.files.existingParents(item.SourcePath); err != nil {
		return err
	}
	info, err := storage.files.root.Lstat(filepath.FromSlash(item.SourcePath))
	if err != nil || !info.Mode().IsRegular() || info.Size() != item.SourceSize {
		return mediadomain.ErrTranscodeChanged
	}
	return nil
}
func (storage *VideoStorage) EncodeVideo(ctx context.Context, id string, item mediadomain.TranscodeItem) (*mediadomain.TranscodeRecord, error) {
	if err := mediadomain.ValidateTranscodeInput(item); err != nil {
		return nil, err
	}
	parent, err := videoParent(id, item)
	if err != nil {
		return nil, err
	}
	if err = storage.verifySourceSize(item); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}
	if storage.probe == nil || storage.encoder == nil {
		return nil, mediadomain.ErrTranscodeUnavailable
	}
	source := filepath.Join(storage.files.root.Name(), filepath.FromSlash(item.SourcePath))
	probe, err := storage.probe.Probe(ctx, source)
	if err != nil {
		return nil, err
	}
	video, err := mediadomain.Analyze(probe)
	if err != nil {
		return nil, err
	}
	if video.PlaybackHint == "browser_candidate" {
		return nil, nil
	}
	if err = mediadomain.ValidateTranscodeSource(video); err != nil {
		return nil, err
	}
	if err = storage.files.existingParents(parent + "/video.mp4"); err != nil {
		return nil, err
	}
	if err = storage.files.root.RemoveAll(filepath.FromSlash(parent)); err != nil {
		return nil, err
	}
	if err = storage.files.directory(parent, 0700); err != nil {
		return nil, err
	}
	artifact, err := storage.encoder.Encode(ctx, mediadomain.VideoEncodeRequest{SourcePath: source, SourceSize: item.SourceSize, OutputDirectory: filepath.Join(storage.files.root.Name(), filepath.FromSlash(parent))})
	if err != nil {
		return nil, err
	}
	relative, err := filepath.Rel(storage.files.root.Name(), artifact.Path)
	if err != nil || !filepath.IsLocal(relative) {
		return nil, mediadomain.ErrTranscodeOutput
	}
	record := mediadomain.TranscodeRecord{StagePath: filepath.ToSlash(relative), Size: artifact.Size, SHA256: artifact.SHA256, Video: artifact.Video}
	if record.Path, err = videoTarget(item, record); err != nil {
		return nil, err
	}
	if err = storage.validateRecord(id, item, record); err != nil {
		return nil, err
	}
	if err = storage.files.syncParents(record.StagePath); err != nil {
		return nil, err
	}
	return &record, nil
}
func (storage *VideoStorage) validateRecord(id string, item mediadomain.TranscodeItem, record mediadomain.TranscodeRecord) error {
	parent, err := videoParent(id, item)
	if err != nil {
		return err
	}
	target, err := videoTarget(item, record)
	if err != nil {
		return err
	}
	if err = mediadomain.ValidateTranscodeRecord(record); err != nil {
		return err
	}
	child := strings.TrimPrefix(record.StagePath, parent+"/")
	if record.Path != target || !strings.HasPrefix(child, ".transcode-") || strings.Count(child, "/") != 1 || path.Base(child) != "video.mp4" || !filepath.IsLocal(record.StagePath) {
		return mediadomain.ErrTranscodeOutput
	}
	return nil
}
func (storage *VideoStorage) verifyOutputSize(name string, record mediadomain.TranscodeRecord) error {
	info, err := storage.files.root.Lstat(filepath.FromSlash(name))
	if err != nil || !info.Mode().IsRegular() || info.Size() != record.Size {
		return mediadomain.ErrTranscodeOutput
	}
	return nil
}
func (storage *VideoStorage) PublishVideo(ctx context.Context, id string, item mediadomain.TranscodeItem) error {
	if item.Output == nil {
		return mediadomain.ErrTranscodeOutput
	}
	record := *item.Output
	if err := storage.validateRecord(id, item, record); err != nil {
		return err
	}
	// 来源一致性由切换事务按快照比较；发布只负责同盘移动产物。
	if err := storage.files.directory(path.Dir(record.Path), 0750); err != nil {
		return err
	}
	if _, err := storage.files.root.Lstat(filepath.FromSlash(record.Path)); err == nil {
		return storage.verifyOutputSize(record.Path, record)
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	err := storage.files.publishFile(ctx, record.StagePath, record.Path, func(name string) error { return storage.verifyOutputSize(name, record) })
	if errors.Is(err, os.ErrExist) {
		return mediadomain.ErrTranscodeOutput
	}
	return err
}
func (storage *VideoStorage) CollectVideo(ctx context.Context, id string, item mediadomain.TranscodeItem) error {
	if item.Output == nil {
		return mediadomain.ErrTranscodeOutput
	}
	if err := storage.validateRecord(id, item, *item.Output); err != nil {
		return err
	}
	if !mediadomain.ValidHQPath(item.SourcePath) || !filepath.IsLocal(item.SourcePath) || item.SourcePath == item.Output.Path {
		return mediadomain.ErrTranscodeInvalid
	}
	if err := storage.verifyOutputSize(item.Output.Path, *item.Output); err != nil {
		return err
	}
	if err := storage.files.existingParents(item.SourcePath); err != nil {
		return err
	}
	if info, err := storage.files.root.Lstat(filepath.FromSlash(item.SourcePath)); err == nil {
		if !info.Mode().IsRegular() || info.Size() != item.SourceSize {
			return mediadomain.ErrTranscodeChanged
		}
		if err = storage.files.root.Remove(filepath.FromSlash(item.SourcePath)); err != nil {
			return err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := storage.files.syncDirectory(path.Dir(item.SourcePath)); err != nil {
		return err
	}
	parent, err := videoParent(id, item)
	if err != nil {
		return err
	}
	if err = storage.files.existingParents(parent + "/video.mp4"); err != nil {
		return err
	}
	if err = storage.files.root.RemoveAll(filepath.FromSlash(parent)); err != nil {
		return err
	}
	if err = storage.files.syncDirectory(path.Dir(parent)); err != nil {
		return err
	}
	return nil
}
