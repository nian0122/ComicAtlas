package application

import (
	"context"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	readingports "comicatlas.local/backend/internal/modules/reading/application/ports"
	readingdomain "comicatlas.local/backend/internal/modules/reading/domain"
)

type Service struct{ repository readingports.Repository }

func New(repository readingports.Repository) *Service { return &Service{repository: repository} }

func (service *Service) Reaction(ctx context.Context, kind, id string) (readingdomain.Reaction, error) {
	if !readingdomain.ValidTarget(kind) {
		return readingdomain.Reaction{}, librarydomain.ErrValidation
	}
	return service.repository.Reaction(ctx, kind, id)
}

func (service *Service) SetReactions(ctx context.Context, changes []readingdomain.ReactionChange) ([]readingdomain.Reaction, error) {
	if !readingdomain.ValidReactionChanges(changes) {
		return nil, librarydomain.ErrValidation
	}
	return service.repository.SetReactions(ctx, changes)
}

func (service *Service) Reactions(ctx context.Context, kind, value string, page, size int, oldest bool) (readingdomain.ReactionPage, error) {
	result := readingdomain.ReactionPage{Items: []readingdomain.Favorite{}, Page: page, PageSize: size}
	if !readingdomain.ValidPage(page, size) || (value != "" && value != "like" && value != "dislike") {
		return result, librarydomain.ErrValidation
	}
	if kind != "" {
		if !readingdomain.ValidTarget(kind) {
			return result, librarydomain.ErrValidation
		}
	}
	result, err := service.repository.Reactions(ctx, kind, value, page, size, oldest)
	for i := range result.Items {
		item := &result.Items[i]
		item.PreviewURL = "/api/v1/comics/" + item.ComicID + "/cover"
		if item.TargetType == "media" && item.MediaType != nil && *item.MediaType == "image" {
			item.PreviewURL = "/api/v1/media/" + item.TargetID + "/content?quality=auto"
		}
	}
	return result, err
}

func (service *Service) History(ctx context.Context, page, size int) (readingdomain.HistoryPage, error) {
	result := readingdomain.HistoryPage{Items: []readingdomain.HistoryEntry{}, Page: page, PageSize: size}
	if !readingdomain.ValidPage(page, size) {
		return result, librarydomain.ErrValidation
	}
	result, err := service.repository.History(ctx, page, size)
	for i := range result.Items {
		result.Items[i].CoverURL = "/api/v1/comics/" + result.Items[i].ComicID + "/cover"
	}
	return result, err
}

func (service *Service) SetReaction(ctx context.Context, kind, id, value string) (readingdomain.Reaction, error) {
	result, err := service.SetReactions(ctx, []readingdomain.ReactionChange{{TargetType: kind, TargetID: id, Value: value}})
	if err != nil {
		return readingdomain.Reaction{}, err
	}
	return result[0], nil
}
