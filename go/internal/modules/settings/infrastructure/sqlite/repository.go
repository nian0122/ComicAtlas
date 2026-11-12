package sqlite

import (
	"context"

	settingsports "comicatlas.local/backend/internal/modules/settings/application/ports"
	settingsdomain "comicatlas.local/backend/internal/modules/settings/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

type sqliteRepository struct{ database *persistence.Database }

func New(database *persistence.Database) *sqliteRepository {
	return &sqliteRepository{database: database}
}

var _ settingsports.Repository = (*sqliteRepository)(nil)

func (service *sqliteRepository) Reader(ctx context.Context) (settingsdomain.Reader, error) {
	var result settingsdomain.Reader
	err := service.database.Reader.QueryRowContext(ctx, "SELECT quality,fit,direction,version FROM reader_settings WHERE singleton=1").Scan(&result.Quality, &result.Fit, &result.Direction, &result.Version)
	return result, err
}

func (service *sqliteRepository) SaveReader(ctx context.Context, value settingsdomain.Reader) (settingsdomain.Reader, error) {
	updated, err := service.database.Writer.ExecContext(ctx, "UPDATE reader_settings SET quality=?,fit=?,direction=?,version=version+1 WHERE singleton=1 AND version=?", value.Quality, value.Fit, value.Direction, value.Version)
	if err != nil {
		return settingsdomain.Reader{}, err
	}
	count, err := updated.RowsAffected()
	if err != nil {
		return settingsdomain.Reader{}, err
	}
	if count != 1 {
		return settingsdomain.Reader{}, settingsdomain.ErrConflict
	}
	value.Version++
	return value, nil
}
