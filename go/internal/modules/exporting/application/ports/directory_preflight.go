package ports

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type DirectoryPreflightRepository interface {
	AdmitDirectoryPreflight(exportdomain.DirectoryPreflightRequest) jobsports.Admission
	LoadDirectoryPreflight(context.Context, string, string) (exportdomain.DirectoryPreflightRequest, error)
	DirectoryPreflightStatus(context.Context, string) (exportdomain.DirectoryPreflightView, error)
	CompleteDirectoryPreflight(context.Context, string, exportdomain.DirectoryPreviewRecord) (persistence.WriteAction, error)
	RetryDirectoryPreflight(string) persistence.WriteAction
}
