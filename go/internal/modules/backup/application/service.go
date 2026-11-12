package application

import (
	"context"
	"crypto/rand"
	"errors"
	"path/filepath"
	"strings"
	"time"

	"comicatlas.local/backend/internal/modules/backup/application/ports"
	"comicatlas.local/backend/internal/modules/backup/domain"
)

type Service struct {
	databases ports.Databases
	catalogs  ports.Catalogs
	files     ports.Filesystem
}

func New(databases ports.Databases, catalogs ports.Catalogs, files ports.Filesystem) *Service {
	return &Service{databases: databases, catalogs: catalogs, files: files}
}

func (service *Service) Create(ctx context.Context, request domain.CreateRequest) (manifest domain.Manifest, result error) {
	if strings.TrimSpace(request.DataDirectory) == "" || strings.TrimSpace(request.MediaDirectory) == "" || strings.TrimSpace(request.Destination) == "" {
		return manifest, domain.ErrValidation
	}
	work, roots, err := service.files.Begin(ctx, request.Destination, []string{request.DataDirectory, request.MediaDirectory})
	if err != nil {
		return manifest, err
	}
	defer closeResult(&result, work.Close)
	if len(roots) != 2 || domain.ValidateSourceDirectories(roots[0], roots[1]) != nil {
		return manifest, domain.ErrValidation
	}
	snapshot, err := service.databases.OpenOffline(ctx, roots[0])
	if err != nil {
		return manifest, err
	}
	defer closeResult(&result, snapshot.Close)
	index, err := service.catalogs.Create(ctx, filepath.Join(work.Root(), domain.CatalogPath))
	if err != nil {
		return manifest, err
	}
	defer closeResult(&result, index.Close)
	data, err := work.Directory(ctx, "data")
	if err != nil {
		return manifest, err
	}
	if err := index.Add(ctx, data); err != nil {
		return manifest, err
	}
	if err := snapshot.Copy(ctx, filepath.Join(work.Root(), filepath.FromSlash(domain.DatabasePath))); err != nil {
		return manifest, err
	}
	database, err := work.File(ctx, domain.DatabasePath)
	if err != nil {
		return manifest, err
	}
	if err := index.Add(ctx, database); err != nil {
		return manifest, err
	}
	if err := work.ImportMedia(ctx, roots[1], func(entry domain.Entry) error { return index.Add(ctx, entry) }); err != nil {
		return manifest, err
	}
	summary, err := index.Seal(ctx)
	if err != nil {
		return manifest, err
	}
	object, err := work.Digest(ctx, domain.CatalogPath)
	if err != nil {
		return manifest, err
	}
	manifest = domain.Manifest{Format: domain.Format, Version: domain.Version, ID: rand.Text(), CreatedAt: time.Now().UTC().Format(time.RFC3339Nano), Database: snapshot.Info(), Catalog: object, Summary: summary}
	if err := work.WriteManifest(ctx, manifest); err != nil {
		return domain.Manifest{}, err
	}
	verified, err := service.Verify(ctx, work.Root())
	if err != nil {
		return domain.Manifest{}, err
	}
	if verified != manifest {
		return domain.Manifest{}, domain.ErrInvalid
	}
	if err := work.Publish(ctx); err != nil {
		return manifest, err
	}
	return manifest, nil
}

func closeResult(result *error, close func() error) { *result = errors.Join(*result, close()) }
