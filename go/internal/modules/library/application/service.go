package application

import (
	"context"

	libraryports "comicatlas.local/backend/internal/modules/library/application/ports"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

type Service struct{ repository libraryports.Repository }

func New(repository libraryports.Repository) *Service { return &Service{repository: repository} }

func (service *Service) Snapshot(ctx context.Context, id string) (librarydomain.Snapshot, error) {
	return service.repository.Snapshot(ctx, id)
}

func (service *Service) SnapshotForSidecar(ctx context.Context, id string) (librarydomain.SidecarSnapshot, error) {
	return service.repository.SnapshotForSidecar(ctx, id)
}
func (service *Service) SnapshotGuard(value librarydomain.Snapshot) persistence.WriteAction {
	return service.repository.SnapshotGuard(value)
}
func (service *Service) StorageWriter(expected, next librarydomain.Snapshot) persistence.WriteAction {
	return service.repository.StorageWriter(expected, next)
}

func (service *Service) Search(ctx context.Context, query librarydomain.ListQuery) (librarydomain.Page, error) {
	if err := librarydomain.ValidateListQuery(query); err != nil {
		return librarydomain.Page{Items: []librarydomain.Comic{}, Page: query.Page, PageSize: query.PageSize}, err
	}
	result, err := service.repository.Search(ctx, query)
	for i := range result.Items {
		result.Items[i].CoverURL = "/api/v1/comics/" + result.Items[i].ID + "/cover"
	}
	return result, err
}

func (service *Service) Detail(ctx context.Context, id string) (librarydomain.Detail, error) {
	result, err := service.repository.Detail(ctx, id)
	if err == nil {
		result.CoverURL = "/api/v1/comics/" + id + "/cover"
	}
	return result, err
}

func (service *Service) Media(ctx context.Context, id string) (librarydomain.Media, error) {
	result, err := service.repository.Media(ctx, id)
	previewURL(&result)
	return result, err
}

func (service *Service) Reader(ctx context.Context, id string) (librarydomain.Reader, error) {
	result, err := service.repository.Reader(ctx, id)
	for i := range result.Items {
		previewURL(&result.Items[i])
	}
	return result, err
}

func previewURL(item *librarydomain.Media) {
	if item.MediaType == "image" {
		url := "/api/v1/media/" + item.ID + "/content?quality=auto"
		item.PreviewURL = &url
	}
}

func (service *Service) Progress(ctx context.Context, comicID string) (*librarydomain.Progress, error) {
	return service.repository.Progress(ctx, comicID)
}

func (service *Service) SaveProgress(ctx context.Context, comicID, chapterID string, page int) (librarydomain.Progress, error) {
	if page < 1 {
		return librarydomain.Progress{}, librarydomain.ErrInvalidProgress
	}
	return service.repository.SaveProgress(ctx, comicID, chapterID, page)
}

func (service *Service) SaveMediaProgress(ctx context.Context, comicID, chapterID, mediaID string) (librarydomain.Progress, error) {
	if mediaID == "" {
		return librarydomain.Progress{}, librarydomain.ErrInvalidProgress
	}
	return service.repository.SaveMediaProgress(ctx, comicID, chapterID, mediaID)
}

func (service *Service) PlanWriter(plan librarydomain.Plan) persistence.WriteAction {
	return service.repository.PlanWriter(plan)
}

func (service *Service) List(ctx context.Context, page, size int, keyword string) (librarydomain.Page, error) {
	return service.Search(ctx, librarydomain.ListQuery{Page: page, PageSize: size, Keyword: keyword})
}
