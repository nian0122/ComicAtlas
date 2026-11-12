package application

import (
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
)

type Service struct{ probe mediaports.Probe }

func New(probe mediaports.Probe) *Service { return &Service{probe: probe} }

func (service *Service) Inspect(ctx context.Context, filename string) (mediadomain.Video, error) {
	if service.probe == nil {
		return mediadomain.Video{}, mediadomain.ErrUnavailable
	}
	probe, err := service.probe.Probe(ctx, filename)
	if err != nil {
		return mediadomain.Video{}, err
	}
	return mediadomain.Analyze(probe)
}
