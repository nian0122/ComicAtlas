package application

import (
	"context"

	settingsports "comicatlas.local/backend/internal/modules/settings/application/ports"
	settingsdomain "comicatlas.local/backend/internal/modules/settings/domain"
)

type Service struct{ repository settingsports.Repository }

func New(repository settingsports.Repository) *Service { return &Service{repository: repository} }

func (service *Service) Reader(ctx context.Context) (settingsdomain.Reader, error) {
	return service.repository.Reader(ctx)
}

func (service *Service) SaveReader(ctx context.Context, value settingsdomain.Reader) (settingsdomain.Reader, error) {
	if value.Version < 1 {
		return settingsdomain.Reader{}, settingsdomain.ErrValidation
	}
	switch value.Quality {
	case "auto", "hq", "lq":
	default:
		return settingsdomain.Reader{}, settingsdomain.ErrValidation
	}
	switch value.Fit {
	case "auto", "width", "height", "original":
	default:
		return settingsdomain.Reader{}, settingsdomain.ErrValidation
	}
	switch value.Direction {
	case "vertical", "horizontal", "ltr", "rtl":
	default:
		return settingsdomain.Reader{}, settingsdomain.ErrValidation
	}
	return service.repository.SaveReader(ctx, value)
}
