package filesystem

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"

	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
)

func (files *Files) digest(ctx context.Context, name string) (int64, string, error) {
	before, err := files.stat(name)
	if err != nil {
		return 0, "", err
	}
	input, err := files.root.Open(filepath.FromSlash(name))
	if err != nil {
		return 0, "", err
	}
	defer input.Close()
	info, err := input.Stat()
	if err != nil {
		return 0, "", err
	}
	if !os.SameFile(before, info) || !info.Mode().IsRegular() {
		return 0, "", lifecycledomain.ErrChanged
	}
	hash := sha256.New()
	buffer := files.buffer[:]
	var size int64
	for {
		if err = ctx.Err(); err != nil {
			return 0, "", err
		}
		count, readErr := input.Read(buffer)
		size += int64(count)
		if size > info.Size() {
			return 0, "", lifecycledomain.ErrChanged
		}
		if count > 0 {
			_, _ = hash.Write(buffer[:count])
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return 0, "", readErr
		}
	}
	after, err := input.Stat()
	if err != nil {
		return 0, "", err
	}
	if size != info.Size() || after.Size() != info.Size() || !after.ModTime().Equal(info.ModTime()) {
		return 0, "", lifecycledomain.ErrChanged
	}
	return size, hex.EncodeToString(hash.Sum(nil)), nil
}
