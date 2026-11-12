package filesystem

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"time"

	"comicatlas.local/backend/internal/modules/backup/domain"
	"comicatlas.local/backend/internal/platform/streamcopy"
)

func descriptor(root *os.Root, name string) (domain.Entry, os.FileInfo, error) {
	if checkedPath(name, false) != nil {
		return domain.Entry{}, nil, domain.ErrInvalid
	}
	info, err := regularPath(root, name)
	if err != nil {
		return domain.Entry{}, nil, err
	}
	entry := domain.Entry{Path: name, Kind: domain.Directory}
	if !info.IsDir() {
		entry.Kind, entry.Size, entry.ModifiedAt = domain.File, info.Size(), info.ModTime().UTC().Format(time.RFC3339Nano)
	}
	if domain.ValidateDescriptor(entry) != nil {
		return domain.Entry{}, nil, domain.ErrSource
	}
	return entry, info, nil
}

func openedRegular(root *os.Root, name string) (*os.File, os.FileInfo, error) {
	info, err := regularPath(root, name)
	if err != nil || !info.Mode().IsRegular() {
		return nil, nil, domain.ErrSource
	}
	file, err := root.Open(filepath.FromSlash(name))
	if err != nil {
		return nil, nil, domain.ErrSource
	}
	actual, err := file.Stat()
	if err != nil || !os.SameFile(info, actual) || !actual.Mode().IsRegular() {
		file.Close()
		return nil, nil, domain.ErrSource
	}
	return file, actual, nil
}

func stream(ctx context.Context, input *os.File, output io.Writer, size int64) (domain.Object, error) {
	value, err := streamcopy.Exact(ctx, input, output, size)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return domain.Object{}, err
		}
		if errors.Is(err, streamcopy.ErrWrite) {
			return domain.Object{}, domain.ErrIO
		}
		return domain.Object{}, domain.ErrSource
	}
	return domain.Object{Size: value.Size, SHA256: value.SHA256}, nil
}

func unchanged(file *os.File, before os.FileInfo) error {
	after, err := file.Stat()
	if err != nil || !os.SameFile(before, after) || before.Size() != after.Size() || !before.ModTime().Equal(after.ModTime()) {
		return domain.ErrSource
	}
	return nil
}

func digestFile(ctx context.Context, root *os.Root, name string) (domain.Object, error) {
	if checkedPath(name, true) != nil {
		return domain.Object{}, domain.ErrInvalid
	}
	file, info, err := openedRegular(root, name)
	if err != nil {
		return domain.Object{}, err
	}
	defer file.Close()
	object, err := stream(ctx, file, io.Discard, info.Size())
	if err != nil {
		return object, err
	}
	return object, unchanged(file, info)
}

func (work *workspace) Digest(ctx context.Context, name string) (domain.Object, error) {
	if work.closed || work.root == nil {
		return domain.Object{}, domain.ErrInvalid
	}
	return digestFile(ctx, work.root, name)
}

func (work *workspace) File(ctx context.Context, name string) (domain.Entry, error) {
	if work.closed || work.root == nil {
		return domain.Entry{}, domain.ErrInvalid
	}
	entry, _, err := descriptor(work.root, name)
	if err != nil || entry.Kind != domain.File {
		return domain.Entry{}, domain.ErrInvalid
	}
	object, err := work.Digest(ctx, name)
	entry.SHA256 = object.SHA256
	return entry, err
}

func (work *workspace) copyFile(ctx context.Context, source *os.Root, sourceName string, expected domain.Entry, restoring bool) (entry domain.Entry, result error) {
	if checkedPath(expected.Path, false) != nil || work.closed || work.root == nil {
		return entry, domain.ErrInvalid
	}
	input, info, err := openedRegular(source, sourceName)
	if err != nil {
		return entry, err
	}
	defer func() {
		if err := input.Close(); err != nil && result == nil {
			result = domain.ErrIO
		}
	}()
	if info.Size() != expected.Size || !restoring && info.ModTime().UTC().Format(time.RFC3339Nano) != expected.ModifiedAt {
		return entry, domain.ErrSource
	}
	output, err := work.root.OpenFile(filepath.FromSlash(expected.Path), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return entry, domain.ErrIO
	}
	defer func() {
		if err := output.Close(); err != nil && result == nil {
			result = domain.ErrIO
		}
	}()
	object, err := stream(ctx, input, output, expected.Size)
	if err != nil {
		return entry, err
	}
	if err := unchanged(input, info); err != nil {
		return entry, err
	}
	if restoring && object.SHA256 != expected.SHA256 {
		return entry, domain.ErrInvalid
	}
	modified, err := time.Parse(time.RFC3339Nano, expected.ModifiedAt)
	if err != nil {
		return entry, domain.ErrInvalid
	}
	if err := work.root.Chtimes(filepath.FromSlash(expected.Path), modified, modified); err != nil {
		return entry, domain.ErrIO
	}
	if err := output.Sync(); err != nil {
		return entry, domain.ErrIO
	}
	entry = expected
	entry.SHA256 = object.SHA256
	return entry, ctx.Err()
}
