package application

import (
	"context"
	"path/filepath"
	"strings"
	"time"

	"comicatlas.local/backend/internal/modules/backup/domain"
)

func (service *Service) Restore(ctx context.Context, request domain.RestoreRequest) (receipt domain.RestoreReceipt, result error) {
	if strings.TrimSpace(request.Source) == "" || strings.TrimSpace(request.Destination) == "" {
		return receipt, domain.ErrValidation
	}
	work, roots, err := service.files.Begin(ctx, request.Destination, []string{request.Source})
	if err != nil {
		return receipt, err
	}
	defer closeResult(&result, work.Close)
	if len(roots) != 1 {
		return receipt, domain.ErrValidation
	}
	manifest, err := service.Verify(ctx, roots[0])
	if err != nil {
		return receipt, err
	}
	source, err := service.files.Open(ctx, roots[0])
	if err != nil {
		return receipt, err
	}
	defer closeResult(&result, source.Close)
	index, err := service.catalogs.Open(ctx, filepath.Join(source.Root(), domain.CatalogPath))
	if err != nil {
		return receipt, err
	}
	defer closeResult(&result, index.Close)
	if err := index.Each(ctx, func(entry domain.Entry) error { return work.ImportEntry(ctx, source.Root(), entry) }); err != nil {
		return receipt, err
	}
	info, err := service.databases.Inspect(ctx, filepath.Join(work.Root(), filepath.FromSlash(domain.DatabasePath)))
	if err != nil {
		return receipt, err
	}
	if info != manifest.Database {
		return receipt, domain.ErrInvalid
	}
	if err := source.CheckExtra(ctx, index); err != nil {
		return receipt, err
	}
	object, err := source.Digest(ctx, domain.CatalogPath)
	if err != nil {
		return receipt, err
	}
	if object != manifest.Catalog {
		return receipt, domain.ErrInvalid
	}
	again, err := source.Manifest(ctx)
	if err != nil {
		return receipt, err
	}
	if again != manifest {
		return receipt, domain.ErrInvalid
	}
	receipt = domain.RestoreReceipt{Format: "comicatlas-restore", Version: domain.Version, BackupID: manifest.ID, RestoredAt: time.Now().UTC().Format(time.RFC3339Nano), Database: manifest.Database, Summary: manifest.Summary}
	if err := work.WriteReceipt(ctx, receipt); err != nil {
		return domain.RestoreReceipt{}, err
	}
	if err := work.Publish(ctx); err != nil {
		return receipt, err
	}
	return receipt, nil
}
