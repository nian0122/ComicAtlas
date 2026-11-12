package application

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
)

func (service *Service) SelectTrashTargets(ctx context.Context, kind string, selection lifecycledomain.TargetSelection) ([]lifecycledomain.SelectedTarget, error) {
	value, err := lifecycledomain.NormalizeTargetSelection(kind, selection)
	if err != nil {
		return nil, err
	}
	return service.repository.SelectTrashTargets(ctx, kind, value)
}

func (service *Service) TrashSnapshotGuarded(ctx context.Context, kind, id, stamp, key string, guard jobsports.Admission) (jobsdomain.Task, error) {
	if guard == nil || !lifecycledomain.ValidTrashStamp(stamp) || lifecycledomain.ValidateTarget(kind, id) != nil || kind == "comic" {
		return jobsdomain.Task{}, lifecycledomain.ErrValidation
	}
	target, err := service.repository.TrashTarget(ctx, kind, id, stamp)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.submit(ctx, target, lifecycledomain.Request{Operation: "trash"}, key, guard, stamp)
}
