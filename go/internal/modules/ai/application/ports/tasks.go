package ports

import (
	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type TaskRepository interface {
	Admission(string, aidomain.AnalysisRequest) jobsports.Admission
	Run(context.Context, string) (aidomain.Run, error)
	Check(context.Context, string) error
	Completion(string, int, aidomain.Request, aidomain.Candidate) persistence.WriteAction
	Retry(string) persistence.WriteAction
}
