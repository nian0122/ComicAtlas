package ports

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"os"
)

type LQRepository interface {
	Scope(context.Context, string, string) (mediadomain.LQScope, error)
	Admission(mediadomain.LQScope) jobsports.Admission
	Begin(context.Context, string) error
	Batch(context.Context, string, int) ([]mediadomain.LQItem, error)
	Progress(context.Context, string) (mediadomain.LQProgress, error)
	Current(context.Context, string) (*mediadomain.LQRecord, string, error)
	Record(context.Context, string, mediadomain.LQItem, *mediadomain.LQRecord, string) error
	Completion(string) persistence.WriteAction
	Items(context.Context, string, int, int) (mediadomain.LQItemPage, error)
	Invalidate(context.Context, mediadomain.LQRecord) error
	Garbage(context.Context) ([]string, error)
	Collected(context.Context, string) error
}
type LQStorage interface {
	Generate(context.Context, string, mediadomain.LQItem, mediadomain.ImageSpec) (mediadomain.LQRecord, error)
	Verify(context.Context, mediadomain.LQRecord) (bool, error)
	Open(mediadomain.LQRecord) (*os.File, error)
	Remove(string) error
}
