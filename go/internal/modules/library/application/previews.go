package application

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"context"
)

func (service *Service) Previews(ctx context.Context, comicID string, nodes []librarydomain.NodeRef) ([]librarydomain.NodePreview, error) {
	if err := librarydomain.ValidatePreviewNodes(nodes); err != nil {
		return nil, err
	}
	items, err := service.repository.Previews(ctx, comicID, nodes)
	if err != nil {
		return nil, err
	}
	for i := range items {
		for j := range items[i].Images {
			image := &items[i].Images[j]
			image.PreviewURL = "/api/v1/media/" + image.MediaID + "/content?quality=auto"
		}
	}
	return items, nil
}
