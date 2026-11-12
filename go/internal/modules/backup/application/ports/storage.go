package ports

import (
	"context"

	"comicatlas.local/backend/internal/modules/backup/domain"
)

type Snapshot interface {
	Info() domain.DatabaseInfo
	Copy(context.Context, string) error
	Close() error
}

type Databases interface {
	OpenOffline(context.Context, string) (Snapshot, error)
	Inspect(context.Context, string) (domain.DatabaseInfo, error)
}

type CatalogWriter interface {
	Add(context.Context, domain.Entry) error
	Seal(context.Context) (domain.Summary, error)
	Close() error
}

type CatalogReader interface {
	Summary(context.Context) (domain.Summary, error)
	Each(context.Context, func(domain.Entry) error) error
	Find(context.Context, string) (domain.Entry, error)
	Close() error
}

type Catalogs interface {
	Create(context.Context, string) (CatalogWriter, error)
	Open(context.Context, string) (CatalogReader, error)
}
