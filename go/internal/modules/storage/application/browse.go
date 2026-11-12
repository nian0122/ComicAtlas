package application

import (
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"context"
)

func (service *Service) Browse(ctx context.Context, kind, parent string, query storagedomain.BrowseQuery) (storagedomain.StoragePage, error) {
	if err := storagedomain.ValidateBrowse(kind, parent, query); err != nil {
		return storagedomain.StoragePage{}, err
	}
	result, err := service.repository.Browse(ctx, kind, parent, query)
	if err != nil {
		return result, err
	}
	for index := range result.Items {
		result.Items[index] = storagedomain.DescribeStorage(result.Items[index])
	}
	return result, nil
}
func (service *Service) Object(ctx context.Context, kind, id string) (storagedomain.StorageObject, error) {
	if err := storagedomain.ValidateScope(kind, id); err != nil || kind == "global" {
		return storagedomain.StorageObject{}, storagedomain.ErrValidation
	}
	result, err := service.repository.Object(ctx, kind, id)
	if err != nil {
		return result, err
	}
	return storagedomain.DescribeStorage(result), nil
}
