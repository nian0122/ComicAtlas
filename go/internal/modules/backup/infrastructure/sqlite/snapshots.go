package sqlite

import (
	"context"
	"errors"

	"comicatlas.local/backend/internal/modules/backup/application/ports"
	"comicatlas.local/backend/internal/modules/backup/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

type Databases struct{}

type snapshot struct{ source *persistence.OfflineSnapshot }

var _ ports.Databases = Databases{}

func (Databases) OpenOffline(ctx context.Context, root string) (ports.Snapshot, error) {
	source, err := persistence.OpenOfflineSnapshot(ctx, root)
	if err != nil {
		return nil, snapshotError(err)
	}
	return &snapshot{source: source}, nil
}

func (Databases) Inspect(ctx context.Context, filename string) (domain.DatabaseInfo, error) {
	info, err := persistence.InspectSnapshotFile(ctx, filename)
	if err != nil {
		return domain.DatabaseInfo{}, snapshotError(err)
	}
	return snapshotInfo(info), nil
}

func (source *snapshot) Info() domain.DatabaseInfo { return snapshotInfo(source.source.Info()) }

func (source *snapshot) Copy(ctx context.Context, filename string) error {
	return snapshotError(source.source.WriteSnapshot(ctx, filename))
}

func (source *snapshot) Close() error { return snapshotError(source.source.Close()) }

func snapshotInfo(info persistence.SnapshotInfo) domain.DatabaseInfo {
	return domain.DatabaseInfo{Format: info.Format, MigrationCount: info.MigrationCount, MigrationChecksum: info.MigrationChecksum}
}

func snapshotError(err error) error {
	switch {
	case err == nil:
		return nil
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		return err
	case errors.Is(err, persistence.ErrAlreadyRunning):
		return domain.ErrBusy
	case errors.Is(err, persistence.ErrSnapshotMissing):
		return domain.ErrSource
	case errors.Is(err, persistence.ErrSnapshotExists):
		return domain.ErrExists
	case errors.Is(err, persistence.ErrSnapshotInvalid):
		return domain.ErrInvalid
	default:
		return domain.ErrIO
	}
}
