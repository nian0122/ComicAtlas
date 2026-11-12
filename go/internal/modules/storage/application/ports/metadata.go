package ports

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type MetadataLibrary interface {
	Snapshot(context.Context, string) (librarydomain.Snapshot, error)
	SnapshotGuard(librarydomain.Snapshot) persistence.WriteAction
	StorageWriter(librarydomain.Snapshot, librarydomain.Snapshot) persistence.WriteAction
}
type MetadataRepository interface {
	MetadataAdmission(librarydomain.Snapshot, persistence.WriteAction) jobsports.Admission
	MetadataRun(context.Context, string) (storagedomain.MetadataRun, error)
	PrepareMetadata(context.Context, string, librarydomain.Snapshot, []storagedomain.MediaObservation, string) error
	ActivateMetadata(context.Context, string, persistence.WriteAction) error
	MetadataCompletion(string, string) persistence.WriteAction
	MetadataItems(context.Context, string, int, int) (storagedomain.MetadataPage, error)
}
type MetadataFiles interface {
	ObserveMetadata(context.Context, librarydomain.Snapshot, func(int) error) ([]storagedomain.MediaObservation, error)
	PublishMetadata(context.Context, string, string, []byte, string) error
}
type MetadataVideo interface {
	Inspect(context.Context, string) (mediadomain.Video, error)
}
