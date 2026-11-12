package ports

import (
	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type ScanSource interface {
	SourcePath(string, bool) (string, error)
	Candidates(context.Context, string) ([]ingestiondomain.ScanTarget, error)
}
type ScanRepository interface {
	Admission(string) jobsports.Admission
	Begin(context.Context, string) error
	Save(context.Context, string, int, ingestiondomain.Candidate) error
	Completion(string) persistence.WriteAction
	List(context.Context, string, int, int) (ingestiondomain.CandidatePage, error)
	Selection(context.Context, string, []string) ([]ingestiondomain.Candidate, error)
	SelectionAdmission(string, []ingestiondomain.Candidate) jobsports.BatchAdmission
	ImportBatchParents(context.Context) ([]string, error)
	StartImportBatch(context.Context, string) error
	ReconcileImportBatch(context.Context, string) (bool, bool, error)
	PendingImportBatchItem(context.Context, string) (int, string, error)
	ActiveImportBatchChildren(context.Context, string) ([]string, error)
	ImportBatchChildAdmission(string, int) jobsports.Admission
	ImportBatchCompletion(string) persistence.WriteAction
}
