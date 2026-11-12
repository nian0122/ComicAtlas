package application

import (
	"context"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

type Capabilities struct{ reader mediaports.CapabilityReader }

func NewCapabilities(reader mediaports.CapabilityReader) *Capabilities {
	return &Capabilities{reader: reader}
}

func (service *Capabilities) Read(ctx context.Context) (mediadomain.Capabilities, error) {
	if service == nil || service.reader == nil {
		return mediadomain.Capabilities{}, mediadomain.ErrCapabilitiesUnavailable
	}
	return service.reader.Read(ctx)
}
