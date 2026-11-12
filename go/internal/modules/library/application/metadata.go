package application

import (
	"context"
	"strings"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

func (service *Service) Terms(ctx context.Context, kind string, page, size int, keyword string) (librarydomain.TermPage, error) {
	if !librarydomain.ValidTermKind(kind) || !librarydomain.ValidPage(page, size) || len(keyword) > 500 {
		return librarydomain.TermPage{Items: []librarydomain.Term{}, Page: page, PageSize: size}, librarydomain.ErrValidation
	}
	return service.repository.Terms(ctx, kind, page, size, keyword)
}

func (service *Service) SaveTerm(ctx context.Context, kind, id, name string, version int) (librarydomain.Term, error) {
	name = strings.TrimSpace(name)
	if !librarydomain.ValidTermKind(kind) || !librarydomain.ValidName(name, 200) || (id != "" && version < 1) {
		return librarydomain.Term{}, librarydomain.ErrValidation
	}
	return service.repository.SaveTerm(ctx, kind, id, name, version)
}

func (service *Service) Term(ctx context.Context, kind, id string) (librarydomain.Term, error) {
	if !librarydomain.ValidTermKind(kind) {
		return librarydomain.Term{}, librarydomain.ErrValidation
	}
	return service.repository.Term(ctx, kind, id)
}

func (service *Service) DeleteTerm(ctx context.Context, kind, id string, version int) error {
	if !librarydomain.ValidTermKind(kind) || version < 1 {
		return librarydomain.ErrValidation
	}
	return service.repository.DeleteTerm(ctx, kind, id, version)
}

func (service *Service) UpdateMetadataBatch(ctx context.Context, changes []librarydomain.MetadataChange) ([]librarydomain.MetadataVersion, error) {
	if len(changes) == 0 || len(changes) > 100 {
		return nil, librarydomain.ErrValidation
	}
	seen := map[string]bool{}
	for _, change := range changes {
		if change.ID == "" || seen[change.ID] {
			return nil, librarydomain.ErrValidation
		}
		seen[change.ID] = true
		if err := librarydomain.ValidateMetadata(change.MetadataUpdate); err != nil {
			return nil, err
		}
	}
	return service.repository.UpdateMetadataBatch(ctx, changes)
}

func (service *Service) UpdateMetadata(ctx context.Context, id string, update librarydomain.MetadataUpdate) (librarydomain.MetadataVersion, error) {
	result, err := service.UpdateMetadataBatch(ctx, []librarydomain.MetadataChange{{ID: id, MetadataUpdate: update}})
	if err != nil {
		return librarydomain.MetadataVersion{}, err
	}
	return result[0], nil
}
