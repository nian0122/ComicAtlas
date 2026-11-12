package filesystem

import (
	"bytes"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
)

func metadataKey(value string) bool {
	if value == "" || len(value) > 128 {
		return false
	}
	for _, c := range value {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}
func (files *MetadataFiles) directory(name string) error {
	current := ""
	for _, part := range strings.Split(name, "/") {
		if current != "" {
			current += "/"
		}
		current += part
		info, err := files.scanner.root.Lstat(filepath.FromSlash(current))
		if errors.Is(err, os.ErrNotExist) {
			if err = files.scanner.root.Mkdir(filepath.FromSlash(current), 0750); err != nil {
				return err
			}
			continue
		}
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return storagedomain.ErrScanRead
		}
	}
	return nil
}
func (files *MetadataFiles) PublishMetadata(ctx context.Context, comic, task string, data []byte, hash string) error {
	if !metadataKey(comic) || !metadataKey(task) || len(data) > 2*librarydomain.MaxSnapshotBytes {
		return storagedomain.ErrValidation
	}
	digest := sha256.Sum256(data)
	if hash != hex.EncodeToString(digest[:]) {
		return storagedomain.ErrValidation
	}
	parent := "staging/metadata/" + task + "/" + comic
	if err := files.directory("metadata"); err != nil {
		return err
	}
	if err := files.directory(parent); err != nil {
		return err
	}
	if err := files.cleanMetadataTemporary(ctx, parent, task); err != nil {
		return err
	}
	var random [8]byte
	if _, err := rand.Read(random[:]); err != nil {
		return err
	}
	temporary := parent + "/." + task + "-" + hex.EncodeToString(random[:]) + ".tmp"
	output, err := files.scanner.root.OpenFile(filepath.FromSlash(temporary), os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0640)
	if err != nil {
		return err
	}
	defer files.scanner.root.Remove(filepath.FromSlash(temporary))
	reader := bytes.NewReader(data)
	buffer := make([]byte, 64<<10)
	for reader.Len() > 0 {
		if err = ctx.Err(); err != nil {
			output.Close()
			return err
		}
		count, readErr := reader.Read(buffer)
		if readErr != nil && readErr != io.EOF {
			output.Close()
			return readErr
		}
		if _, err = output.Write(buffer[:count]); err != nil {
			output.Close()
			return err
		}
	}
	err = errors.Join(output.Sync(), output.Close())
	if err != nil {
		return err
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	if err = files.scanner.root.Rename(filepath.FromSlash(temporary), filepath.FromSlash("metadata/"+comic+".json")); err != nil {
		return err
	}
	for _, name := range []string{parent, "metadata", "."} {
		if err = files.scanner.syncDirectory(name); err != nil {
			return err
		}
	}
	return nil
}

func (files *MetadataFiles) cleanMetadataTemporary(ctx context.Context, parent, task string) error {
	directory, err := files.scanner.root.Open(filepath.FromSlash(parent))
	if err != nil {
		return err
	}
	defer directory.Close()
	count := 0
	prefix := "." + task + "-"
	for {
		entries, readErr := directory.ReadDir(128)
		if readErr != nil && readErr != io.EOF {
			return readErr
		}
		for _, entry := range entries {
			if err = ctx.Err(); err != nil {
				return err
			}
			count++
			if count > 4096 {
				return storagedomain.ErrScanLimit
			}
			name := entry.Name()
			if entry.IsDir() || !strings.HasPrefix(name, prefix) || !strings.HasSuffix(name, ".tmp") {
				continue
			}
			key := strings.TrimSuffix(strings.TrimPrefix(name, prefix), ".tmp")
			if len(key) != 16 {
				continue
			}
			if _, err = hex.DecodeString(key); err != nil {
				continue
			}
			if err = files.scanner.root.Remove(filepath.FromSlash(parent + "/" + name)); err != nil {
				return err
			}
		}
		if readErr == io.EOF {
			return nil
		}
	}
}
