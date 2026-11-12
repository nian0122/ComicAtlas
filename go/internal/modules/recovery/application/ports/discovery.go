package ports

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
)

type DiscoverySource interface {
	ScanHQ(context.Context, func(string) error) (recoverydomain.Discovery, error)
}

type DiscoveryRepository interface {
	DiscoveryAdmission() jobsports.Admission
	BeginDiscovery(context.Context, string) (recoverydomain.DiscoveryRun, error)
	AppendDiscovery(context.Context, string, []string) error
	SealDiscovery(context.Context, string, recoverydomain.Discovery) (recoverydomain.DiscoveryRun, error)
	Discovery(context.Context, string) (recoverydomain.DiscoveryRun, error)
	Candidates(context.Context, string, string, int) ([]string, error)
}
