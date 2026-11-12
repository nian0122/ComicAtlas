package ports

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type UploadPublishRepository interface {
	UploadAdmission(mediadomain.UploadSession, string) jobsports.Admission
	UploadJob(context.Context, string) (mediadomain.UploadSession, error)
	UploadBatch(context.Context, string, int) ([]mediadomain.UploadPublishFile, error)
	UploadPrepared(context.Context, string, mediadomain.UploadPublishFile, mediadomain.Inspection) error
	UploadPublished(context.Context, string, string) error
	UploadFileFailure(context.Context, string, string, string) error
	UploadProgress(context.Context, string) (int, int, error)
	ActivateUpload(context.Context, string) error
	UploadRetirement(context.Context, string) ([]mediadomain.UploadRetired, error)
	UploadRetired(context.Context, string, string, string) error
	UploadCompletion(string) persistence.WriteAction
	UploadItems(context.Context, string, int, int) (mediadomain.UploadPublishPage, error)
}
type UploadPublishStorage interface {
	InspectUpload(context.Context, string, mediadomain.UploadPublishFile) (mediadomain.Inspection, error)
	PublishUpload(context.Context, string, mediadomain.UploadPublishFile) error
	RetireUpload(context.Context, string, mediadomain.UploadPublishFile, mediadomain.UploadRetired) (string, error)
	RemoveUploadStage(context.Context, string) error
}
