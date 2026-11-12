package ports

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"io"
)

type UploadRepository interface {
	CreateUpload(context.Context, mediadomain.UploadSession, string, string, mediadomain.UploadSpace, mediadomain.UploadLimits) (string, error)
	UploadSession(context.Context, string) (mediadomain.UploadSession, error)
	UploadFile(context.Context, string, string) (mediadomain.UploadSession, mediadomain.UploadFile, error)
	UploadPart(context.Context, string, string, int) (*mediadomain.UploadPart, error)
	RecordUploadPart(context.Context, string, mediadomain.UploadPart, string) error
	EndUpload(context.Context, string, string, string) error
	ExpireUploads(context.Context, string) error
	UploadCleanup(context.Context, string) ([]string, error)
	UploadCleaned(context.Context, string) error
}
type UploadStorage interface {
	UploadSpace(context.Context) (mediadomain.UploadSpace, error)
	ReceiveUploadPart(context.Context, string, mediadomain.UploadFile, mediadomain.UploadRange, io.Reader, *mediadomain.UploadPart) (mediadomain.UploadPart, error)
	RemoveUpload(context.Context, mediadomain.UploadSession) error
}
