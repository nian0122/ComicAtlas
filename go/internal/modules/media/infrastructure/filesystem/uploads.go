package filesystem

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/diskspace"
)

var _ mediaports.UploadStorage = (*Storage)(nil)

func uploadDirectory(id string) (string, error) {
	if !mediadomain.UploadID(id, "us_") {
		return "", mediadomain.ErrUploadValidation
	}
	return "staging/uploads/" + id, nil
}
func (storage *Storage) ReceiveUploadPart(ctx context.Context, id string, file mediadomain.UploadFile, extent mediadomain.UploadRange, input io.Reader, previous *mediadomain.UploadPart) (part mediadomain.UploadPart, err error) {
	part = mediadomain.UploadPart{FileID: file.ID, Part: extent.Part, Size: extent.Length}
	if !mediadomain.UploadID(file.ID, "uf_") || extent.Total != file.Size || extent.Start != int64(extent.Part)*mediadomain.UploadChunkBytes {
		return part, mediadomain.ErrUploadValidation
	}
	if err = mediadomain.ValidateUploadPart(mediadomain.UploadPart{Part: extent.Part, Size: extent.Length}, file.Size); err != nil {
		return part, err
	}
	parent, err := uploadDirectory(id)
	if err != nil {
		return part, err
	}
	if err = storage.directory(parent, 0700); err != nil {
		return part, mediadomain.ErrUploadIO
	}
	name := parent + "/" + file.ID + ".part"
	before, statErr := storage.root.Lstat(filepath.FromSlash(name))
	if statErr == nil && (!before.Mode().IsRegular() || before.Size() > file.Size) {
		return part, mediadomain.ErrUploadIO
	}
	if statErr != nil && !errors.Is(statErr, os.ErrNotExist) {
		return part, mediadomain.ErrUploadIO
	}
	created := errors.Is(statErr, os.ErrNotExist)
	flags := os.O_RDWR
	if created {
		flags |= os.O_CREATE | os.O_EXCL
	}
	output, err := storage.root.OpenFile(filepath.FromSlash(name), flags, 0600)
	if err != nil && created {
		// 同一文件的并行分片可能已先创建组装文件，重新按已存在文件打开。
		if before, statErr = storage.root.Lstat(filepath.FromSlash(name)); statErr == nil && before.Mode().IsRegular() && before.Size() <= file.Size {
			created = false
			output, err = storage.root.OpenFile(filepath.FromSlash(name), os.O_RDWR, 0600)
		}
	}
	if err != nil {
		return part, mediadomain.ErrUploadIO
	}
	defer output.Close()
	opened, err := output.Stat()
	if err != nil || !opened.Mode().IsRegular() || !created && !os.SameFile(before, opened) {
		return part, mediadomain.ErrUploadIO
	}
	if _, err = output.Seek(extent.Start, io.SeekStart); err != nil {
		return part, mediadomain.ErrUploadIO
	}
	buffer := make([]byte, 256<<10)
	old := make([]byte, len(buffer))
	limited := uploadReader{ctx: ctx, reader: io.LimitReader(input, extent.Length+1)}
	compare := previous != nil
	if compare && previous.Size != extent.Length {
		return part, mediadomain.ErrUploadContentChanged
	}
	var written int64
	for {
		if err := ctx.Err(); err != nil {
			return part, err
		}
		size := int64(len(buffer))
		if remaining := extent.Length - written; remaining < size {
			size = remaining + 1
		}
		count, readErr := limited.Read(buffer[:size])
		if count > 0 {
			if written+int64(count) > extent.Length {
				return part, mediadomain.ErrUploadValidation
			}
			if compare {
				if _, err = output.ReadAt(old[:count], extent.Start+written); err != nil {
					return part, mediadomain.ErrUploadIO
				}
				if !bytes.Equal(old[:count], buffer[:count]) {
					return part, mediadomain.ErrUploadContentChanged
				}
			}
			chunk := buffer[:count]
			for len(chunk) > 0 {
				n, writeErr := output.Write(chunk)
				if writeErr != nil || n == 0 {
					return part, mediadomain.ErrUploadIO
				}
				chunk = chunk[n:]
			}
			written += int64(count)
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			if ctx.Err() != nil {
				return part, ctx.Err()
			}
			return part, mediadomain.ErrUploadIO
		}
	}
	if written != extent.Length {
		return part, mediadomain.ErrUploadValidation
	}
	if err = output.Sync(); err != nil {
		return part, mediadomain.ErrUploadIO
	}
	if created {
		if err = storage.syncDirectory(parent); err != nil {
			return part, mediadomain.ErrUploadIO
		}
	}
	return part, nil
}

type uploadReader struct {
	ctx    context.Context
	reader io.Reader
}

func (reader uploadReader) Read(buffer []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	return reader.reader.Read(buffer)
}
func (storage *Storage) UploadSpace(ctx context.Context) (mediadomain.UploadSpace, error) {
	if err := ctx.Err(); err != nil {
		return mediadomain.UploadSpace{}, err
	}
	space, err := diskspace.Measure(storage.root.Name())
	if err != nil {
		return mediadomain.UploadSpace{}, mediadomain.ErrUploadIO
	}
	return mediadomain.UploadSpace{Total: space.Total, Available: space.Available}, nil
}
func (storage *Storage) RemoveUpload(ctx context.Context, session mediadomain.UploadSession) error {
	if session.Status != "cancelled" && session.Status != "expired" {
		return mediadomain.ErrUploadConflict
	}
	if err := storage.removeUploadStage(ctx, session.ID); err != nil {
		return err
	}
	for _, file := range session.CleanupFiles {
		comic, chapter, ok := mediadomain.HQOwner(file.Path)
		name := path.Base(file.Path)
		if !ok || comic != session.ComicID || chapter != session.ChapterID || !mediadomain.UploadID(file.ID, "uf_") || strings.TrimSuffix(name, path.Ext(name)) != file.ID || mediadomain.UploadMediaKind(name) == "" {
			return mediadomain.ErrUploadConflict
		}
		if err := storage.existingParents(file.Path); err != nil {
			return err
		}
		if _, err := storage.root.Lstat(file.Path); errors.Is(err, os.ErrNotExist) {
			continue
		} else if err != nil {
			return err
		}
		if err := storage.verifyFileAttributes(ctx, file.Path, file.Size, 0); err != nil {
			return err
		}
		if err := storage.root.Remove(file.Path); err != nil {
			return err
		}
		if err := storage.syncDirectory(path.Dir(file.Path)); err != nil {
			return err
		}
	}
	return nil
}
func (storage *Storage) removeUploadStage(ctx context.Context, id string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	parent, err := uploadDirectory(id)
	if err != nil {
		return err
	}
	if err = storage.existingParents(parent + "/file.part"); err != nil {
		return mediadomain.ErrUploadIO
	}
	if err = storage.root.RemoveAll(filepath.FromSlash(parent)); err != nil {
		return mediadomain.ErrUploadIO
	}
	if _, err = storage.root.Lstat("staging/uploads"); errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return mediadomain.ErrUploadIO
	}
	return storage.syncDirectory("staging/uploads")
}
