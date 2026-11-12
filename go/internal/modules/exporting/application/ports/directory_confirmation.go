package ports

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	"context"
	"time"
)

type DirectoryConfirmationRepository interface {
	DirectoryConfirmationState(context.Context, string) (exportdomain.DirectoryPreviewRecord, error)
	DirectoryAdmissionScope(context.Context, string) (exportdomain.DirectoryAdmissionScope, error)
	DirectoryAdmission(exportdomain.DirectoryPreviewRecord, exportdomain.DirectoryAdmissionScope, string, time.Time) jobsports.Admission
}

type DirectoryFileFence interface {
	AcquireFileMutation(context.Context) (func(), error)
}
