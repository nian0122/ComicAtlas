package ports

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type RepairRepository interface {
	RepairAdmission(string, recoverydomain.RepairRequest) jobsports.Admission
	RepairRun(context.Context, string) (recoverydomain.RepairRun, error)
	RepairRetry(string) persistence.WriteAction
	RepairWriter(recoverydomain.RepairRun, recoverydomain.Sidecar, int, string, string) persistence.WriteAction
}
