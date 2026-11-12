package ports

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type Repository interface {
	Browse(context.Context, string, string, storagedomain.BrowseQuery) (storagedomain.StoragePage, error)
	Object(context.Context, string, string) (storagedomain.StorageObject, error)
	Inventory(context.Context, string, string) (storagedomain.Inventory, error)
	MetricsDirty(context.Context) (bool, error)
	RefreshMetrics(context.Context, int) (int, error)
	GlobalDetails(context.Context) (*storagedomain.Observation, *storagedomain.Refresh, error)
	Admission() jobsports.Admission
	Completion(storagedomain.Observation) persistence.WriteAction
}
type Scanner interface {
	Scan(context.Context, func(int) error) (storagedomain.ObservedUsage, error)
}

type CapacityReader interface {
	Read(context.Context) (storagedomain.DeviceSpace, error)
}
