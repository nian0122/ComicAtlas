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

type imageService interface {
	Render(context.Context, mediadomain.ImageRequest) (mediadomain.ImageArtifact, error)
}
type Storage struct {
	root   *os.Root
	images imageService
}

func New(path string, images imageService) (*Storage, error) {
	path, err := filepath.Abs(path)
	if err != nil {
		return nil, err
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
	for _, name := range []string{"lq", "staging/lq"} {
		if err = root.MkdirAll(name, 0750); err != nil {
			root.Close()
			return nil, err
		}
	}
	return &Storage{root: root, images: images}, nil
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
	key := sha256.Sum256([]byte(item.MediaID))
	segment := hex.EncodeToString(key[:])
	stage := "staging/lq/" + taskID + "/" + segment
	if err = storage.directory("staging/lq/"+taskID, 0700); err != nil {
		return record, err
	}
	if err = storage.root.RemoveAll(filepath.FromSlash(stage)); err != nil {
		return record, mediadomain.ErrImageIO
	}
	if err = storage.directory(stage, 0700); err != nil {
		return record, mediadomain.ErrImageIO
	}
	defer func() {
		if cleanup := storage.root.RemoveAll(filepath.FromSlash(stage)); cleanup != nil {
			err = errors.Join(err, mediadomain.ErrImageIO)
		}
	}()
	artifact, err := storage.images.Render(ctx, mediadomain.ImageRequest{SourcePath: filepath.Join(storage.root.Name(), filepath.FromSlash(item.SourcePath)), SourceSHA256: item.SourceSHA256, WorkRoot: filepath.Join(storage.root.Name(), filepath.FromSlash(stage)), Spec: spec})
	if err != nil {
		return record, err
	}
	relative, err := filepath.Rel(storage.root.Name(), artifact.Path)
	if err != nil || !filepath.IsLocal(relative) || !strings.HasPrefix(filepath.ToSlash(relative), stage+"/") {
		return record, mediadomain.ErrImageOutput
	}
	file, err := storage.root.OpenFile(relative, os.O_RDWR, 0)
	if err != nil {
		return record, mediadomain.ErrImageIO
	}
	syncErr := file.Sync()
	closeErr := file.Close()
	if syncErr != nil || closeErr != nil {
		return record, mediadomain.ErrImageIO
	}
	if err = ctx.Err(); err != nil {
		return record, err
	}
	revision := sha256.Sum256([]byte(taskID + "\x00" + item.MediaID + "\x00" + artifact.SHA256))
	target := mediadomain.DerivedPath("lq", item.SourcePath, item.MediaID, hex.EncodeToString(revision[:]), "webp")
	if target == "" {
		return record, mediadomain.ErrLQValidation
	}
	parent := path.Dir(target)
	if err = storage.directory(parent, 0750); err != nil {
		return record, mediadomain.ErrImageIO
	}

	verify := func(name string) error {
		return storage.hashHQ(ctx, name, mediadomain.HQReleaseItem{OriginalBytes: artifact.Size, SourceSHA256: artifact.SHA256})
	}
	err = storage.publishFile(ctx, filepath.ToSlash(relative), target, verify)
	if errors.Is(err, os.ErrExist) {
		// 同一任务重试可复用已核验的版本输出；不同内容拒绝覆盖。
		err = verify(target)
		if err == nil {
			err = storage.syncPublishedFile(target)
		}
	}
	if err != nil {
		return record, err
	}

	if err = storage.syncParents(target); err != nil {
		return record, err
	}
	if err = storage.syncDirectory(path.Dir(filepath.ToSlash(relative))); err != nil {
		return record, err
	}
	return mediadomain.LQRecord{MediaID: item.MediaID, SourceSHA256: artifact.SourceSHA256, Path: target, SHA256: artifact.SHA256, Size: artifact.Size, Width: artifact.Width, Height: artifact.Height, Spec: &spec, TaskID: taskID, Valid: true}, nil
}

func (storage *Storage) directory(path string, mode os.FileMode) error {
	prefix := ""
	for _, part := range strings.Split(path, "/") {
		if prefix != "" {
			prefix += "/"
		}
		prefix += part
		info, err := storage.root.Lstat(filepath.FromSlash(prefix))
		if errors.Is(err, os.ErrNotExist) {
			if err = storage.root.Mkdir(filepath.FromSlash(prefix), mode); err != nil {
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
func (storage *Storage) Verify(ctx context.Context, record mediadomain.LQRecord) (bool, error) {
	file, err := storage.Open(record)
	if err != nil {
		return false, nil
	}
	defer file.Close()
	hash := sha256.New()
	buffer := make([]byte, 64<<10)
	var total int64
	for {
		if err = ctx.Err(); err != nil {
			return false, err
		}
		count, readErr := file.Read(buffer)
		if count > 0 {
			total += int64(count)
			if total > mediadomain.MaxImageOutputBytes {
				return false, nil
			}
			_, _ = hash.Write(buffer[:count])
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return false, nil
		}
	}
	return total == record.Size && hex.EncodeToString(hash.Sum(nil)) == record.SHA256, nil
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
