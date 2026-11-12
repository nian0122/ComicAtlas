package filesystem

import (
	"context"
	"io"
	"os"

	"comicatlas.local/backend/internal/modules/backup/application/ports"
	"comicatlas.local/backend/internal/modules/backup/domain"
)

type bundle struct{ root *os.Root }

func (Filesystem) Open(ctx context.Context, name string) (ports.Bundle, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	canonical, err := canonicalDirectory(name)
	if err != nil {
		return nil, err
	}
	root, err := os.OpenRoot(canonical)
	if err != nil {
		return nil, domain.ErrSource
	}
	return &bundle{root: root}, nil
}

func (source *bundle) Root() string { return source.root.Name() }

func (source *bundle) Close() error {
	if source.root.Close() != nil {
		return domain.ErrIO
	}
	return nil
}

func (source *bundle) Manifest(ctx context.Context) (domain.Manifest, error) {
	if err := ctx.Err(); err != nil {
		return domain.Manifest{}, err
	}
	file, info, err := openedRegular(source.root, domain.ManifestPath)
	if err != nil || info.Size() > domain.MaxManifestBytes {
		if file != nil {
			file.Close()
		}
		return domain.Manifest{}, domain.ErrInvalid
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, domain.MaxManifestBytes+1))
	if err != nil || unchanged(file, info) != nil {
		return domain.Manifest{}, domain.ErrInvalid
	}
	if err := ctx.Err(); err != nil {
		return domain.Manifest{}, err
	}
	return domain.DecodeManifest(data)
}

func (source *bundle) Digest(ctx context.Context, name string) (domain.Object, error) {
	return digestFile(ctx, source.root, name)
}

func (source *bundle) Check(ctx context.Context, expected domain.Entry) error {
	if domain.ValidateEntry(expected) != nil {
		return domain.ErrInvalid
	}
	entry, _, err := descriptor(source.root, expected.Path)
	if err != nil || entry.Kind != expected.Kind || entry.Size != expected.Size {
		return domain.ErrInvalid
	}
	if entry.Kind == domain.File {
		object, err := source.Digest(ctx, entry.Path)
		if err != nil {
			return err
		}
		if object.SHA256 != expected.SHA256 {
			return domain.ErrInvalid
		}
	}
	return ctx.Err()
}

func (source *bundle) CheckExtra(ctx context.Context, catalog ports.CatalogReader) error {
	// 固定元文件不登记为负载；其他每个实际文件和目录都必须在索引中。
	var observed domain.Summary
	err := walk(ctx, source.root, ".", 0, func(name string, info os.FileInfo) error {
		if name == domain.ManifestPath || name == domain.CatalogPath {
			if !info.Mode().IsRegular() {
				return domain.ErrInvalid
			}
			return nil
		}
		if checkedPath(name, false) != nil {
			return domain.ErrInvalid
		}
		expected, err := catalog.Find(ctx, name)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return domain.ErrInvalid
		}
		if info.IsDir() {
			if expected.Kind != domain.Directory {
				return domain.ErrInvalid
			}
		} else if expected.Kind != domain.File || expected.Size != info.Size() {
			return domain.ErrInvalid
		}
		observed, err = domain.Add(observed, expected)
		if err != nil {
			return domain.ErrInvalid
		}
		return nil
	})
	if err != nil {
		return err
	}
	expected, err := catalog.Summary(ctx)
	if err != nil {
		return err
	}
	if expected != observed {
		return domain.ErrInvalid
	}
	return nil
}
