package application

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	readingdomain "comicatlas.local/backend/internal/modules/reading/domain"
	"context"
)

func (service *Service) ManagedReactions(ctx context.Context, query readingdomain.ManagementQuery) (readingdomain.ManagedReactionPage, error) {
	if !readingdomain.ValidManagementQuery(query) {
		return readingdomain.ManagedReactionPage{}, librarydomain.ErrValidation
	}
	return service.repository.ManagedReactions(ctx, query)
}

func (service *Service) SetManagedReactions(ctx context.Context, changes []readingdomain.ReactionChange) ([]readingdomain.Reaction, error) {
	if !readingdomain.ValidReactionChanges(changes) {
		return nil, librarydomain.ErrValidation
	}
	return service.repository.SetManagedReactions(ctx, changes)
}
