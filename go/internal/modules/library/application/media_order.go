package application

import (
	"context"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

func (service *Service) ReorderMedia(ctx context.Context, chapterID string, version int, ids []string) (librarydomain.MediaOrder, error) {
	if version < 1 || ids == nil || len(ids) > 10000 {
		return librarydomain.MediaOrder{}, librarydomain.ErrValidation
	}
	seen := map[string]bool{}
	for _, id := range ids {
		if id == "" || seen[id] {
			return librarydomain.MediaOrder{}, librarydomain.ErrValidation
		}
		seen[id] = true
	}
	return service.repository.ReorderMedia(ctx, chapterID, version, ids)
}
