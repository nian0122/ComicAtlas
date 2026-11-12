package ports

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"time"
)

type Library interface {
	CheckPatchReferences(context.Context, librarydomain.MetadataPatch) error
	SelectComics(context.Context, librarydomain.ComicSelection) ([]librarydomain.SelectedComic, error)
	SelectHQComics(context.Context, librarydomain.ComicSelection) ([]librarydomain.SelectedComic, error)
	MetadataPatchWriter(string, int, librarydomain.MetadataPatch) persistence.WriteAction
}
type Repository interface {
	CommandRepository
	CreatePreview(context.Context, string, string, string, []maintenancedomain.Item, time.Time) (maintenancedomain.Preview, error)
	Preview(context.Context, string) (maintenancedomain.Record, error)
	Items(context.Context, string, int, int) (maintenancedomain.ItemPage, error)
	Admission(string, string) jobsports.Admission
	Next(context.Context, string) (maintenancedomain.Item, error)
	Apply(context.Context, string, maintenancedomain.Item, persistence.WriteAction) error
	Completion(string) persistence.WriteAction
}
