package filesystem

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"

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
	if err = mediadomain.ValidateUploadPart(mediadomain.UploadPart{Part: extent.Part, Size: extent.Length, SHA256: file.SHA256}, file.Size); err != nil {
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
	hash := sha256.New()
	buffer := make([]byte, 256<<10)
	size, err := io.CopyBuffer(io.MultiWriter(output, hash), uploadReader{ctx: ctx, reader: io.LimitReader(input, extent.Length+1)}, buffer)
	if err != nil {
		if ctx.Err() != nil {
			return part, ctx.Err()
		}
		return part, mediadomain.ErrUploadIO
	}
	if size != extent.Length {
		return part, mediadomain.ErrUploadValidation
	}
	part.SHA256 = hex.EncodeToString(hash.Sum(nil))
	if previous != nil && (previous.SHA256 != part.SHA256 || previous.Size != part.Size) {
		return part, mediadomain.ErrUploadChecksum
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
		if !ok || comic != session.ComicID || chapter != session.ChapterID {
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
		if err := storage.hashHQ(ctx, file.Path, mediadomain.HQReleaseItem{OriginalBytes: file.Size, SourceSHA256: file.SHA256}); err != nil {
			return err
		}
		if err := storage.root.Remove(file.Path); err != nil {
			return err
		}
		if err := storage.syncParents(file.Path); err != nil {
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
