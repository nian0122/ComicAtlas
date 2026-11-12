package ports

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type HQRepository interface {
	Scope(context.Context, string, string) (mediadomain.LQScope, error)
	HQPreview(context.Context, mediadomain.LQScope) (mediadomain.HQReleasePreview, error)
	HQAdmission(mediadomain.LQScope) jobsports.Admission
	HQBegin(context.Context, string) error
	HQBatch(context.Context, string, int) ([]mediadomain.HQReleaseItem, error)
	HQCurrent(context.Context, string) (mediadomain.HQCurrent, error)
	HQPrepare(context.Context, string, mediadomain.HQReleaseItem) error
	HQRecord(context.Context, string, mediadomain.HQReleaseItem, string) error
	HQFail(context.Context, string, mediadomain.HQReleaseItem, string) error
	HQProgress(context.Context, string) (int, int, error)
	HQCompletion(string) persistence.WriteAction
	HQItems(context.Context, string, int, int) (mediadomain.HQReleasePage, error)
}
type HQStorage interface {
	Verify(context.Context, mediadomain.LQRecord) (bool, error)
	VerifyHQ(context.Context, mediadomain.HQReleaseItem) error
	ReleaseHQ(context.Context, string, mediadomain.HQReleaseItem) (string, error)
}
