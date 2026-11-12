package filesystem

import (
	"context"

	storageports "comicatlas.local/backend/internal/modules/storage/application/ports"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"comicatlas.local/backend/internal/platform/diskspace"
)

type Capacity struct{ path string }

func NewCapacity(path string) *Capacity { return &Capacity{path: path} }

func (capacity *Capacity) Read(ctx context.Context) (storagedomain.DeviceSpace, error) {
	if err := ctx.Err(); err != nil {
		return storagedomain.DeviceSpace{}, err
	}
	snapshot, err := diskspace.Measure(capacity.path)
	if err != nil {
		return storagedomain.DeviceSpace{}, err
	}
	return storagedomain.DeviceSpace{TotalBytes: snapshot.Total, AvailableBytes: snapshot.Available}, nil
}

var _ storageports.CapacityReader = (*Capacity)(nil)
