package ports

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type RestoreRepository interface {
	Records
	Discovery(context.Context, string) (recoverydomain.DiscoveryRun, error)
	CommitOutcome(context.Context, recoverydomain.Outcome, persistence.WriteAction) (recoverydomain.Outcome, error)
	RestoreWriter(string, recoverydomain.Sidecar, string, string) persistence.WriteAction
	PlaceholderWriter(string, string, string, recoverydomain.HQPresence) persistence.WriteAction
}
