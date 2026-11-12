package ports

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type Repository interface {
	Cancelled(context.Context, string) (bool, error)
	Title(context.Context, string) (string, error)
	Admission(string, exportdomain.Request) jobsports.Admission
	Load(context.Context, string) (exportdomain.Snapshot, string, error)
	Completion(exportdomain.Manifest) persistence.WriteAction
	Artifacts(context.Context, string) (exportdomain.ArtifactView, error)
}
type Files interface {
	CleanupCancelled(context.Context, func(context.Context, string) (bool, error)) (int, error)
	Build(context.Context, string, exportdomain.Snapshot, func(int) error) ([]exportdomain.Artifact, error)
	Publish(context.Context, string, []exportdomain.Artifact) error
	Cleanup(context.Context, string) error
}
