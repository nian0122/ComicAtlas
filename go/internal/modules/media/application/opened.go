package application

import (
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"os"
)

type OpenedInspector struct{ probe mediaports.OpenedProbe }

func NewOpenedInspector(probe mediaports.OpenedProbe) *OpenedInspector {
	return &OpenedInspector{probe: probe}
}

func (inspector *OpenedInspector) InspectOpened(ctx context.Context, input *os.File, logicalName string) (mediadomain.Video, error) {
	if inspector.probe == nil {
		return mediadomain.Video{}, mediadomain.ErrUnavailable
	}
	value, err := inspector.probe.ProbeOpened(ctx, input, logicalName)
	if err != nil {
		return mediadomain.Video{}, err
	}
	return mediadomain.Analyze(value)
}
