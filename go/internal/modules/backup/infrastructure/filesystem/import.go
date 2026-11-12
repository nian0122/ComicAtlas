package filesystem

import (
	"context"
	"os"
	"path"
	"time"

	"comicatlas.local/backend/internal/modules/backup/domain"
)

func (work *workspace) source(name string) (*os.Root, error) {
	before, ok := work.sources[name]
	if !ok {
		return nil, domain.ErrSource
	}
	after, err := os.Stat(name)
	if err != nil || !os.SameFile(before, after) {
		return nil, domain.ErrSource
	}
	root, err := os.OpenRoot(name)
	if err != nil {
		return nil, domain.ErrSource
	}
	return root, nil
}

func (work *workspace) ImportMedia(ctx context.Context, name string, consume func(domain.Entry) error) error {
	root, err := work.source(name)
	if err != nil {
		return err
	}
	defer root.Close()
	entry, err := work.Directory(ctx, "media")
	if err != nil {
		return err
	}
	if err := consume(entry); err != nil {
		return err
	}
	return walk(ctx, root, ".", 0, func(relative string, info os.FileInfo) error {
		entry := domain.Entry{Path: path.Join("media", relative), Kind: domain.Directory}
		if info.IsDir() {
			if _, err := work.Directory(ctx, entry.Path); err != nil {
				return err
			}
		} else {
			entry.Kind, entry.Size, entry.ModifiedAt = domain.File, info.Size(), info.ModTime().UTC().Format(time.RFC3339Nano)
			if domain.ValidateDescriptor(entry) != nil {
				return domain.ErrSource
			}
			var err error
			entry, err = work.copyFile(ctx, root, relative, entry, false)
			if err != nil {
				return err
			}
		}
		return consume(entry)
	})
}

func (work *workspace) ImportEntry(ctx context.Context, name string, entry domain.Entry) error {
	if domain.ValidateEntry(entry) != nil {
		return domain.ErrInvalid
	}
	root, err := work.source(name)
	if err != nil {
		return err
	}
	defer root.Close()
	if entry.Kind == domain.Directory {
		actual, _, err := descriptor(root, entry.Path)
		if err != nil || actual.Kind != entry.Kind {
			return domain.ErrInvalid
		}
		_, err = work.Directory(ctx, entry.Path)
		return err
	}
	_, err = work.copyFile(ctx, root, entry.Path, entry, true)
	return err
}
