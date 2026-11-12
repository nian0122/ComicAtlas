package ports

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type SidecarLibrary interface {
	SnapshotForSidecar(context.Context, string) (librarydomain.SidecarSnapshot, error)
}
type SidecarFiles interface {
	PublishMetadata(context.Context, string, string, []byte, string) error
}
type SidecarFence interface {
	AcquireFileMutation(context.Context) (func(), error)
}
type SidecarRepository interface {
	SidecarState(context.Context, string) (storagedomain.SidecarState, error)
	SidecarCandidates(context.Context, string) ([]storagedomain.SidecarState, error)
	SidecarAdmission(string, bool) jobsports.Admission
	SidecarRetry(string) persistence.WriteAction
	SidecarResult(context.Context, string) (storagedomain.SidecarResult, error)
	PrepareSidecar(context.Context, string, int64, string, string) error
	SidecarCompletion(string, string) persistence.WriteAction
}
