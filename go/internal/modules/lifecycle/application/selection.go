package application

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
)

func (service *Service) SelectEntries(ctx context.Context, selection lifecycledomain.EntrySelection) ([]lifecycledomain.SelectedEntry, error) {
	value, err := lifecycledomain.NormalizeEntrySelection(selection)
	if err != nil {
		return nil, err
	}
	return service.repository.SelectEntries(ctx, value)
}
