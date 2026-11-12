package filesystem

import (
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"
)

var _ mediaports.CoverStorage = (*Storage)(nil)

func (storage *Storage) BuildCover(ctx context.Context, comic string, source mediadomain.ImageSource) (mediadomain.CoverRecord, error) {
	if !mediadomain.StorageKey(comic) {
		return mediadomain.CoverRecord{}, mediadomain.ErrImageInvalid
	}
	return storage.buildCover(ctx, source, comic)
}
func (storage *Storage) buildCover(ctx context.Context, source mediadomain.ImageSource, comic string) (record mediadomain.CoverRecord, err error) {
	if !filepath.IsLocal(source.Path) || strings.ContainsAny(source.Path, "\\:") || (!strings.HasPrefix(source.Path, "hq/") && !strings.HasPrefix(source.Path, "lq/")) {
		return record, mediadomain.ErrImageInvalid
	}
	if err = storage.existingParents(source.Path); err != nil {
		return record, err
	}
	info, err := storage.root.Lstat(filepath.FromSlash(source.Path))
	if err != nil || !info.Mode().IsRegular() || info.Size() != source.Size {
		return record, mediadomain.ErrImageChanged
	}
	if err = storage.directory("staging/covers", 0700); err != nil {
		return record, err
	}
	stage, err := os.MkdirTemp(filepath.Join(storage.root.Name(), "staging", "covers"), "cover-")
	if err != nil {
		return record, mediadomain.ErrImageIO
	}
	defer func() {
		if cleanup := os.RemoveAll(stage); cleanup != nil {
			err = errors.Join(err, mediadomain.ErrImageIO)
		}
	}()
	artifact, err := storage.images.Render(ctx, mediadomain.ImageRequest{SourcePath: filepath.Join(storage.root.Name(), filepath.FromSlash(source.Path)), SourceSHA256: source.SHA256, WorkRoot: stage, Spec: mediadomain.CoverSpec()})
	if err != nil {
		return record, err
	}
	relative, err := filepath.Rel(stage, artifact.Path)
	if err != nil || !filepath.IsLocal(relative) || artifact.Size < 1 || artifact.Size > 2<<20 {
		return record, mediadomain.ErrImageOutput
	}
	key := mediadomain.CoverKey(source)
	record = mediadomain.CoverRecord{MediaID: source.MediaID, SourceKey: key, Path: mediadomain.CoverPath(comic), SHA256: artifact.SHA256, Size: artifact.Size, Width: artifact.Width, Height: artifact.Height}
	if !mediadomain.ValidCover(record, source, comic) {
		return record, mediadomain.ErrImageOutput
	}
	file, err := os.OpenFile(artifact.Path, os.O_RDWR, 0)
	if err != nil {
		return record, mediadomain.ErrImageIO
	}
	err = errors.Join(file.Sync(), file.Close())
	if err != nil {
		return record, mediadomain.ErrImageIO
	}
	if err = storage.directory(path.Dir(record.Path), 0750); err != nil {
		return record, err
	}
	if err = ctx.Err(); err != nil {
		return record, err
	}
	if err = os.Rename(artifact.Path, filepath.Join(storage.root.Name(), filepath.FromSlash(record.Path))); err != nil {
		return record, mediadomain.ErrImageIO
	}
	if err = storage.syncParents(record.Path); err != nil {
		return record, err
	}
	if err = storage.syncDirectory("staging/covers"); err != nil {
		return record, err
	}
	verified, err := storage.OpenCover(ctx, record)
	if err != nil {
		_ = storage.root.Remove(filepath.FromSlash(record.Path))
		return record, err
	}
	verified.Close()
	return record, nil
}

// BuildCoverFromFrame 将视频抽帧得到的图片渲染为独立封面，并记录视频媒体作为来源证据。
func (storage *Storage) BuildCoverFromFrame(ctx context.Context, comic, media string, frame []byte) (record mediadomain.CoverRecord, err error) {
	if !mediadomain.StorageKey(comic) || !mediadomain.StorageKey(media) || len(frame) < 12 {
		return record, mediadomain.ErrImageInvalid
	}
	if err = storage.directory("staging/covers", 0700); err != nil {
		return record, err
	}
	stage, err := os.MkdirTemp(filepath.Join(storage.root.Name(), "staging", "covers"), "frame-")
	if err != nil {
		return record, mediadomain.ErrImageIO
	}
	defer func() {
		if cleanup := os.RemoveAll(stage); cleanup != nil {
			err = errors.Join(err, mediadomain.ErrImageIO)
		}
	}()
	source := filepath.Join(stage, "frame.img")
	if err = os.WriteFile(source, frame, 0600); err != nil {
		return record, mediadomain.ErrImageIO
	}
	digest := sha256.Sum256(frame)
	artifact, err := storage.images.Render(ctx, mediadomain.ImageRequest{
		SourcePath: source, SourceSHA256: hex.EncodeToString(digest[:]), WorkRoot: stage, Spec: mediadomain.CoverSpec(),
	})
	if err != nil {
		return record, err
	}
	if relative, relErr := filepath.Rel(stage, artifact.Path); relErr != nil || !filepath.IsLocal(relative) {
		return record, mediadomain.ErrImageOutput
	}
	if artifact.Size < 1 || artifact.Size > 2<<20 {
		return record, mediadomain.ErrImageOutput
	}
	record = mediadomain.CoverRecord{MediaID: media, SourceKey: mediadomain.FrameCoverKey(media, artifact.SHA256), Path: mediadomain.CoverPath(comic), SHA256: artifact.SHA256, Size: artifact.Size, Width: artifact.Width, Height: artifact.Height}
	if record.Path == "" {
		return record, mediadomain.ErrImageOutput
	}
	if err = storage.directory(path.Dir(record.Path), 0750); err != nil {
		return record, err
	}
	if err = os.Rename(artifact.Path, filepath.Join(storage.root.Name(), filepath.FromSlash(record.Path))); err != nil {
		return record, mediadomain.ErrImageIO
	}
	if err = storage.syncParents(record.Path); err != nil {
		return record, err
	}
	verified, err := storage.OpenCover(ctx, record)
	if err != nil {
		_ = storage.root.Remove(filepath.FromSlash(record.Path))
		return record, err
	}
	verified.Close()
	return record, nil
}

func (storage *Storage) MediaPath(name string) (string, error) {
	if !filepath.IsLocal(name) || strings.ContainsAny(name, "\\:") || (!strings.HasPrefix(name, "hq/") && !strings.HasPrefix(name, "lq/")) {
		return "", mediadomain.ErrImageInvalid
	}
	info, err := storage.root.Lstat(filepath.FromSlash(name))
	if err != nil {
		return "", err
	}
	if !info.Mode().IsRegular() {
		return "", mediadomain.ErrImageInvalid
	}
	return filepath.Join(storage.root.Name(), filepath.FromSlash(name)), nil
}

func (storage *Storage) OpenCover(ctx context.Context, record mediadomain.CoverRecord) (*os.File, error) {
	if !mediadomain.ValidCoverPath(record.Path) {
		return nil, mediadomain.ErrImageOutput
	}
	return storage.openPreview(ctx, record)
}

// OpenStoredCover 只读加载已有的独立封面文件；不生成、不写数据库。
func (storage *Storage) OpenStoredCover(ctx context.Context, comic string) (*os.File, string, error) {
	name := mediadomain.CoverPath(comic)
	if name == "" {
		return nil, "", mediadomain.ErrImageInvalid
	}
	before, err := storage.root.Lstat(filepath.FromSlash(name))
	if err != nil {
		return nil, "", err
	}
	if !before.Mode().IsRegular() || before.Size() < 12 || before.Size() > 2<<20 {
		return nil, "", mediadomain.ErrImageOutput
	}
	file, err := storage.root.Open(filepath.FromSlash(name))
	if err != nil {
		return nil, "", err
	}
	fail := func(err error) (*os.File, string, error) {
		file.Close()
		return nil, "", err
	}
	info, err := file.Stat()
	if err != nil || !os.SameFile(before, info) {
		return fail(mediadomain.ErrImageOutput)
	}
	header := make([]byte, 12)
	if _, err = io.ReadFull(file, header); err != nil {
		return fail(mediadomain.ErrImageInvalid)
	}
	if string(header[0:4]) != "RIFF" || string(header[8:12]) != "WEBP" {
		return fail(mediadomain.ErrImageInvalid)
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return fail(mediadomain.ErrImageIO)
	}
	if err = ctx.Err(); err != nil {
		return fail(err)
	}
	hash := sha256.New()
	buffer := make([]byte, 64<<10)
	var total int64
	for {
		n, readErr := file.Read(buffer)
		total += int64(n)
		if total > before.Size() {
			return fail(mediadomain.ErrImageOutput)
		}
		_, _ = hash.Write(buffer[:n])
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return fail(readErr)
		}
	}
	if total != before.Size() {
		return fail(mediadomain.ErrImageOutput)
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return fail(mediadomain.ErrImageIO)
	}
	return file, hex.EncodeToString(hash.Sum(nil)), nil
}
func (storage *Storage) openPreview(ctx context.Context, record mediadomain.CoverRecord) (*os.File, error) {
	if record.Size < 1 || record.Size > 2<<20 {
		return nil, mediadomain.ErrImageOutput
	}
	if err := storage.existingParents(record.Path); err != nil {
		return nil, err
	}
	before, err := storage.root.Lstat(filepath.FromSlash(record.Path))
	if err != nil {
		return nil, err
	}
	if !before.Mode().IsRegular() || before.Size() != record.Size {
		return nil, mediadomain.ErrImageOutput
	}
	file, err := storage.root.Open(filepath.FromSlash(record.Path))
	if err != nil {
		return nil, err
	}
	fail := func(err error) (*os.File, error) { file.Close(); return nil, err }
	info, err := file.Stat()
	if err != nil || !os.SameFile(before, info) {
		return fail(mediadomain.ErrImageOutput)
	}
	hash := sha256.New()
	buffer := make([]byte, 64<<10)
	var total int64
	for {
		if err = ctx.Err(); err != nil {
			return fail(err)
		}
		n, readErr := file.Read(buffer)
		total += int64(n)
		if total > record.Size {
			return fail(mediadomain.ErrImageOutput)
		}
		_, _ = hash.Write(buffer[:n])
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return fail(readErr)
		}
	}
	if total != record.Size || hex.EncodeToString(hash.Sum(nil)) != record.SHA256 {
		return fail(mediadomain.ErrImageOutput)
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return fail(err)
	}
	return file, nil
}
