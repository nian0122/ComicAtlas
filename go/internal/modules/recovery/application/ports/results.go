package ports

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type ResultRepository interface {
	CommitOutcome(context.Context, recoverydomain.Outcome, persistence.WriteAction) (recoverydomain.Outcome, error)
	PendingCandidates(context.Context, string, string, int) ([]string, error)
	Summary(context.Context, string) (recoverydomain.ResultSummary, error)
}
