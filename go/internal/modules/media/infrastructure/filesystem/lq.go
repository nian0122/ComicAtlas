package filesystem

import (
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/streamcopy"
	"context"
	"errors"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"

	"golang.org/x/image/webp"
)

type imageService interface {
	Render(context.Context, mediadomain.ImageRequest) (mediadomain.ImageArtifact, error)
}
type Storage struct {
	root    *os.Root
	images  imageService
	scratch string
}

// New 的 scratch 只保存可重建的渲染中间文件；生成型 LQ 由快速暂存单次写入媒体根。
func New(path string, images imageService, scratch string) (*Storage, error) {
	path, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	if scratch == "" || !filepath.IsAbs(scratch) {
		return nil, mediadomain.ErrImageIO
	}
	if err = os.MkdirAll(path, 0750); err != nil {
		return nil, err
	}
	path, err = filepath.EvalSymlinks(path)
	if err != nil {
		return nil, err
	}
	root, err := os.OpenRoot(path)
	if err != nil {
		return nil, err
	}
	if err = root.MkdirAll("lq", 0750); err != nil {
		root.Close()
		return nil, err
	}
	return &Storage{root: root, images: images, scratch: scratch}, nil
}
func (storage *Storage) Close() error { return storage.root.Close() }

var _ mediaports.LQStorage = (*Storage)(nil)

func safeKey(value string) bool {
	if value == "" || len(value) > 128 {
		return false
	}
	for _, c := range value {
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '_' && c != '-' {
			return false
		}
	}
	return true
}

// Generate 在快速暂存中渲染，再把产物单次写入媒体根的最终路径。
// 生成型 LQ 只是 HQ 的可重建派生副本，不做逐张 fsync，也不经过媒体卷暂存目录与重命名发布；
// 记录只在校验产物后写入，未登记的半成品不会被读取，后续同任务重试会覆盖同一路径。
func (storage *Storage) Generate(ctx context.Context, taskID string, item mediadomain.LQItem, spec mediadomain.ImageSpec) (record mediadomain.LQRecord, err error) {
	if !safeKey(taskID) || !filepath.IsLocal(item.SourcePath) || !strings.HasPrefix(item.SourcePath, "hq/") || strings.Contains(item.SourcePath, "\\") {
		return record, mediadomain.ErrLQValidation
	}
	if err = storage.existingParents(item.SourcePath); err != nil {
		return record, err
	}
	info, err := storage.root.Lstat(filepath.FromSlash(item.SourcePath))
	if errors.Is(err, os.ErrNotExist) {
		return record, mediadomain.ErrLQNotFound
	}
	if err != nil || !info.Mode().IsRegular() {
		return record, mediadomain.ErrImageIO
	}
	if info.Size() != item.SourceSize || item.SourceModified != 0 && info.ModTime().UnixNano() != item.SourceModified {
		return record, mediadomain.ErrImageChanged
	}
	target := mediadomain.LQPath(item.SourcePath)
	if target == "" {
		return record, mediadomain.ErrLQValidation
	}
	if err = storage.directory(path.Dir(target), 0750); err != nil {
		return record, err
	}
	artifact, err := storage.images.Render(ctx, mediadomain.ImageRequest{SourcePath: filepath.Join(storage.root.Name(), filepath.FromSlash(item.SourcePath)), WorkRoot: storage.scratch, Spec: spec})
	if err != nil {
		return record, err
	}
	defer func() { _ = os.RemoveAll(artifact.TemporaryDirectory) }()
	current, statErr := storage.root.Lstat(filepath.FromSlash(item.SourcePath))
	if statErr != nil || !os.SameFile(info, current) || current.Size() != info.Size() || !current.ModTime().Equal(info.ModTime()) {
		return record, mediadomain.ErrImageChanged
	}
	if err = ctx.Err(); err != nil {
		return record, err
	}
	if err = storage.writeArtifact(ctx, artifact, target, item.MediaID); err != nil {
		return record, err
	}
	return mediadomain.LQRecord{MediaID: item.MediaID, Path: target, Size: artifact.Size, Width: artifact.Width, Height: artifact.Height, Spec: &spec, TaskID: taskID, Valid: true}, nil
}

// writeArtifact 用固定缓冲把已校验的临时产物写入同目录临时文件，再原子替换最终路径。
// 覆盖已有副本时读者只会看到完整旧文件或完整新文件；临时文件按媒体固定命名，重试覆盖，失败删除。
func (storage *Storage) writeArtifact(ctx context.Context, artifact mediadomain.ImageArtifact, target, media string) error {
	if !mediadomain.ValidLQPath(target) || !filepath.IsLocal(target) || strings.Contains(target, "\\") || !safeKey(media) {
		return mediadomain.ErrImageOutput
	}
	if err := storage.existingParents(target); err != nil {
		return err
	}
	temporary := target + "." + media + ".tmp"
	input, err := os.Open(artifact.Path)
	if err != nil {
		return mediadomain.ErrImageIO
	}
	defer input.Close()
	if info, statErr := input.Stat(); statErr != nil || !info.Mode().IsRegular() || info.Size() != artifact.Size {
		return mediadomain.ErrImageOutput
	}
	output, err := storage.root.OpenFile(filepath.FromSlash(temporary), os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0644)
	if err != nil {
		return mediadomain.ErrImageIO
	}
	copyErr := streamcopy.Exact(ctx, input, output, artifact.Size)
	closeErr := output.Close()
	if copyErr != nil || closeErr != nil {
		_ = storage.root.Remove(filepath.FromSlash(temporary))
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return mediadomain.ErrImageIO
	}
	if err = storage.root.Rename(filepath.FromSlash(temporary), filepath.FromSlash(target)); err != nil {
		_ = storage.root.Remove(filepath.FromSlash(temporary))
		return mediadomain.ErrImageIO
	}
	return nil
}

func (storage *Storage) directory(name string, mode os.FileMode) error {
	prefix := ""
	for _, part := range strings.Split(name, "/") {
		if prefix != "" {
			prefix += "/"
		}
		prefix += part
		info, err := storage.root.Lstat(filepath.FromSlash(prefix))
		if errors.Is(err, os.ErrNotExist) {
			if err = storage.root.Mkdir(filepath.FromSlash(prefix), mode); err != nil {
				// 同一任务的并行工作协程可能同时创建相同目录；已存在时按目录复核。
				if !errors.Is(err, os.ErrExist) {
					return mediadomain.ErrImageIO
				}
				info, err = storage.root.Lstat(filepath.FromSlash(prefix))
				if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
					return mediadomain.ErrImageIO
				}
			}
			// 新建目录时同步父级；之后的文件发布只需同步条目所在目录。
			if err = storage.syncDirectory(path.Dir(prefix)); err != nil {
				return mediadomain.ErrImageIO
			}
			continue
		}
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return mediadomain.ErrImageIO
		}
	}
	return nil
}

func (storage *Storage) Open(record mediadomain.LQRecord) (*os.File, error) {
	if !mediadomain.ValidLQPath(record.Path) || !filepath.IsLocal(record.Path) {
		return nil, mediadomain.ErrImageOutput
	}
	if err := storage.existingParents(record.Path); err != nil {
		return nil, err
	}
	name := filepath.FromSlash(record.Path)
	before, err := storage.root.Lstat(name)
	if err != nil {
		return nil, err
	}
	if !before.Mode().IsRegular() {
		return nil, mediadomain.ErrImageOutput
	}
	file, err := storage.root.Open(name)
	if err != nil {
		return nil, err
	}
	after, err := file.Stat()
	if err != nil || !after.Mode().IsRegular() || !os.SameFile(before, after) || after.Size() != record.Size {
		file.Close()
		return nil, mediadomain.ErrImageOutput
	}
	return file, nil
}

// Intact 只核对副本文件身份、大小和 WebP 头尺寸，不做整帧解码；用于批量跳过等非破坏性判断。
func (storage *Storage) Intact(ctx context.Context, record mediadomain.LQRecord) (bool, error) {
	file, err := storage.Open(record)
	if err != nil {
		return false, nil
	}
	defer file.Close()
	if record.Size < 1 || record.Size > mediadomain.MaxImageOutputBytes {
		return false, nil
	}
	config, err := webp.DecodeConfig(io.LimitReader(file, record.Size))
	if err != nil || config.Width < 1 || config.Height < 1 || record.Width > 0 && config.Width != record.Width || record.Height > 0 && config.Height != record.Height || int64(config.Width)*int64(config.Height) > mediadomain.MaxImageDecodePixels {
		return false, ctx.Err()
	}
	return true, ctx.Err()
}

// Verify 在 Intact 基础上再解码整帧，供 HQ 释放等会破坏原件的路径使用。
func (storage *Storage) Verify(ctx context.Context, record mediadomain.LQRecord) (bool, error) {
	valid, err := storage.Intact(ctx, record)
	if err != nil || !valid {
		return false, err
	}
	file, err := storage.Open(record)
	if err != nil {
		return false, nil
	}
	defer file.Close()
	_, err = webp.Decode(io.LimitReader(file, record.Size))
	return err == nil, ctx.Err()
}

func (storage *Storage) Remove(path string) error {
	if !mediadomain.ValidLQPath(path) || !filepath.IsLocal(path) {
		return mediadomain.ErrLQValidation
	}
	if err := storage.existingParents(path); err != nil {
		return err
	}
	err := storage.root.Remove(filepath.FromSlash(path))
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

// 根内链接也不能让 hq/lq 等目录的用途发生别名替换。
func (storage *Storage) existingParents(name string) error {
	parts := strings.Split(filepath.ToSlash(name), "/")
	prefix := ""
	for _, part := range parts[:len(parts)-1] {
		if prefix != "" {
			prefix += "/"
		}
		prefix += part
		info, err := storage.root.Lstat(filepath.FromSlash(prefix))
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return mediadomain.ErrImageOutput
		}
	}
	return nil
}
