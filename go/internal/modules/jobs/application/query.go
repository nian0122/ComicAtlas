package application

import (
	"context"

	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
)

func (store *Store) Events(ctx context.Context, id string, after int64, limit int) (jobsdomain.TaskEventPage, error) {
	if id == "" || after < 0 || limit < 1 || limit > 100 {
		return jobsdomain.TaskEventPage{}, jobsdomain.ErrValidation
	}
	return store.repository.Events(ctx, id, after, limit)
}

func (store *Store) List(ctx context.Context, query jobsdomain.ListQuery) (jobsdomain.TaskPage, error) {
	if err := jobsdomain.ValidateListQuery(query); err != nil {
		return jobsdomain.TaskPage{}, err
	}
	return store.repository.List(ctx, query)
}
func (store *Store) Summary(ctx context.Context, filter jobsdomain.Filter) (jobsdomain.Summary, error) {
	if err := jobsdomain.ValidateFilter(filter); err != nil {
		return jobsdomain.Summary{}, err
	}
	return store.repository.Summary(ctx, filter)
}
func (store *Store) Inspect(ctx context.Context, id string) (jobsdomain.Task, error) {
	return store.repository.Inspect(ctx, id)
}
