package ports

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type TaskRepository interface {
	RestoreRepository
	DiscoveryRepository
	ResultRepository
	RecoveryCompletion(string, int) persistence.WriteAction
	ResultPage(context.Context, string, string, int) (recoverydomain.ResultPage, error)
	Placeholder(context.Context, string) (recoverydomain.Placeholder, error)
}

type TaskSources interface {
	Observations
	DiscoverySource
}
