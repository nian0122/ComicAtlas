package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
)

type ImportBatchCoordinator struct {
	service *ScanService
	cancel  func(context.Context, string) error
	wake    func()
	mutex   sync.Mutex
	wakeup  chan struct{}
}

func NewImportBatchCoordinator(service *ScanService, cancel func(context.Context, string) error, wake func()) *ImportBatchCoordinator {
	return &ImportBatchCoordinator{service: service, cancel: cancel, wake: wake, wakeup: make(chan struct{}, 1)}
}

func (coordinator *ImportBatchCoordinator) Wake() {
	select {
	case coordinator.wakeup <- struct{}{}:
	default:
	}
}

func (coordinator *ImportBatchCoordinator) Step(ctx context.Context) error {
	coordinator.mutex.Lock()
	defer coordinator.mutex.Unlock()
	parents, err := coordinator.service.repository.ImportBatchParents(ctx)
	if err != nil {
		return err
	}
	for _, id := range parents {
		if err = coordinator.stepParent(ctx, id); err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return err
		}
	}
	return nil
}

func (coordinator *ImportBatchCoordinator) stepParent(ctx context.Context, id string) error {
	if err := coordinator.service.repository.StartImportBatch(ctx, id); err != nil {
		return err
	}
	done, cancelling, err := coordinator.service.repository.ReconcileImportBatch(ctx, id)
	if err != nil || done {
		if err != nil {
			return err
		}
		if cancelling {
			return coordinator.service.jobs.FinishCoordinatorCancellation(ctx, id)
		}
		return coordinator.service.jobs.Complete(ctx, id, jobsports.Completion{Persist: coordinator.service.repository.ImportBatchCompletion(id)})
	}
	parent, err := coordinator.service.jobs.Inspect(ctx, id)
	if err != nil {
		return err
	}
	if parent.Status == jobsdomain.TaskStatusCancelling {
		children, err := coordinator.service.repository.ActiveImportBatchChildren(ctx, id)
		if err != nil {
			return err
		}
		for _, childID := range children {
			child, err := coordinator.service.jobs.Inspect(ctx, childID)
			if err != nil {
				return err
			}
			if jobsdomain.CanCancel(child) {
				if err = coordinator.cancel(ctx, childID); err != nil && !errors.Is(err, jobsdomain.ErrConflict) {
					return err
				}
			}
		}
		return nil
	}
	if parent.Status != jobsdomain.TaskStatusRunning {
		return nil
	}
	position, data, err := coordinator.service.repository.PendingImportBatchItem(ctx, id)
	if errors.Is(err, jobsdomain.ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	var input jobsdomain.PreparedImport
	if err = json.Unmarshal([]byte(data), &input); err != nil {
		return err
	}
	key := fmt.Sprintf("batch:%s:%d", parent.TargetID, position)
	_, err = coordinator.service.jobs.CreatePreparedImportTask(ctx, input, key, parent.TargetID, position, coordinator.service.repository.ImportBatchChildAdmission(id, position))
	if errors.Is(err, jobsdomain.ErrFull) || errors.Is(err, jobsdomain.ErrConflict) {
		return nil
	}
	if err != nil {
		return err
	}
	coordinator.wake()
	return nil
}

func (coordinator *ImportBatchCoordinator) Maintain(ctx context.Context, onError func(error)) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		if err := coordinator.Step(ctx); err != nil && ctx.Err() == nil && onError != nil {
			onError(err)
		}
		select {
		case <-ctx.Done():
			return
		case <-coordinator.wakeup:
		case <-ticker.C:
		}
	}
}
