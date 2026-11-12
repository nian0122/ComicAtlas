package application

import (
	"context"
	"path/filepath"

	"comicatlas.local/backend/internal/modules/backup/domain"
)

func (service *Service) Verify(ctx context.Context, name string) (manifest domain.Manifest, result error) {
	source, err := service.files.Open(ctx, name)
	if err != nil {
		return manifest, err
	}
	defer closeResult(&result, source.Close)
	manifest, err = source.Manifest(ctx)
	if err != nil {
		return manifest, err
	}
	object, err := source.Digest(ctx, domain.CatalogPath)
	if err != nil {
		return manifest, err
	}
	if object != manifest.Catalog {
		return manifest, domain.ErrInvalid
	}
	index, err := service.catalogs.Open(ctx, filepath.Join(source.Root(), domain.CatalogPath))
	if err != nil {
		return manifest, err
	}
	defer closeResult(&result, index.Close)
	summary, err := index.Summary(ctx)
	if err != nil {
		return manifest, err
	}
	if summary != manifest.Summary {
		return manifest, domain.ErrInvalid
	}
	if err := index.Each(ctx, func(entry domain.Entry) error { return source.Check(ctx, entry) }); err != nil {
		return manifest, err
	}
	if err := source.CheckExtra(ctx, index); err != nil {
		return manifest, err
	}
	info, err := service.databases.Inspect(ctx, filepath.Join(source.Root(), filepath.FromSlash(domain.DatabasePath)))
	if err != nil {
		return manifest, err
	}
	if info != manifest.Database {
		return manifest, domain.ErrInvalid
	}
	object, err = source.Digest(ctx, domain.CatalogPath)
	if err != nil {
		return manifest, err
	}
	if object != manifest.Catalog {
		return manifest, domain.ErrInvalid
	}
	again, err := source.Manifest(ctx)
	if err != nil {
		return manifest, err
	}
	if again != manifest {
		return manifest, domain.ErrInvalid
	}
	return manifest, nil
}
