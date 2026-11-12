package filesystem

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"

	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

// ResolveImageSource 只核对用户当前请求的单个图片，不枚举或扫描媒体库。
func (storage *Storage) ResolveImageSource(ctx context.Context, source mediadomain.ImageSource) (mediadomain.ImageSource, error) {
	if !filepath.IsLocal(source.Path) || source.SHA256 != "" {
		return source, mediadomain.ErrImageInvalid
	}
	if err := storage.existingParents(source.Path); err != nil {
		return source, err
	}
	file, err := storage.root.Open(filepath.FromSlash(source.Path))
	if err != nil {
		return source, err
	}
	defer file.Close()
	before, err := file.Stat()
	if err != nil || !before.Mode().IsRegular() || before.Size() < 1 || before.Size() > mediadomain.MaxImageInputBytes || (source.Size != 0 && source.Size != before.Size()) {
		return source, mediadomain.ErrImageChanged
	}
	hash := sha256.New()
	buffer := make([]byte, 64<<10)
	for {
		if err := ctx.Err(); err != nil {
			return source, err
		}
		count, err := file.Read(buffer)
		_, _ = hash.Write(buffer[:count])
		if err == io.EOF {
			break
		}
		if err != nil {
			return source, err
		}
	}
	after, err := file.Stat()
	current, pathErr := storage.root.Lstat(filepath.FromSlash(source.Path))
	if err != nil || pathErr != nil || !os.SameFile(before, after) || !os.SameFile(before, current) || before.Size() != after.Size() || !before.ModTime().Equal(after.ModTime()) {
		return source, mediadomain.ErrImageChanged
	}
	source.SHA256, source.Size = hex.EncodeToString(hash.Sum(nil)), before.Size()
	return source, nil
}
