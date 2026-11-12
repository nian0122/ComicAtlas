package application

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

func (service *Service) SelectComics(ctx context.Context, selection librarydomain.ComicSelection) ([]librarydomain.SelectedComic, error) {
	value, err := librarydomain.NormalizeSelection(selection)
	if err != nil {
		return nil, err
	}
	return service.repository.SelectComics(ctx, value)
}
func (service *Service) SelectHQComics(ctx context.Context, selection librarydomain.ComicSelection) ([]librarydomain.SelectedComic, error) {
	value, err := librarydomain.NormalizeSelection(selection)
	if err != nil {
		return nil, err
	}
	return service.repository.SelectHQComics(ctx, value)
}
func (service *Service) MetadataPatchWriter(id string, version int, patch librarydomain.MetadataPatch) persistence.WriteAction {
	return service.repository.MetadataPatchWriter(id, version, patch)
}

func (service *Service) CheckPatchReferences(ctx context.Context, patch librarydomain.MetadataPatch) error {
	if err := librarydomain.ValidateMetadataPatch(patch); err != nil {
		return err
	}
	return service.repository.CheckPatchReferences(ctx, patch)
}
