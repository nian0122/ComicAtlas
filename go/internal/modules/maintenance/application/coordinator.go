package application

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"
)

type Coordinator struct {
	service *Service
	cancel  func(context.Context, string) error
	wake    func()
	mutex   sync.Mutex
}

func NewCoordinator(service *Service, cancel func(context.Context, string) error, wake func()) *Coordinator {
	return &Coordinator{service: service, cancel: cancel, wake: wake}
}
func (coordinator *Coordinator) Step(ctx context.Context) error {
	coordinator.mutex.Lock()
	defer coordinator.mutex.Unlock()
	parents, err := coordinator.service.repository.CommandParents(ctx)
	if err != nil {
		return err
	}
	for _, id := range parents {
		if err = coordinator.invokeParent(ctx, id); err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if failure := coordinator.service.repository.FailCommand(ctx, id); failure != nil {
				return failure
			}
			return err
		}
	}
	return nil
}
func (coordinator *Coordinator) invokeParent(ctx context.Context, id string) (err error) {
	defer func() {
		if recover() != nil {
			err = jobsdomain.NewFailure(jobsdomain.FailureInternal, nil)
		}
	}()
	return coordinator.stepParent(ctx, id)
}
func (coordinator *Coordinator) stepParent(ctx context.Context, id string) error {
	service := coordinator.service
	parent, err := service.repository.StartCommand(ctx, id)
	if errors.Is(err, jobsdomain.ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	done, err := service.repository.ReconcileCommand(ctx, id)
	if err != nil || done {
		return err
	}
	// 重新读取持久取消状态；子任务准入事务仍会复核，覆盖此处之后的竞争。
	task, err := service.jobs.Inspect(ctx, id)
	if err != nil {
		return err
	}
	if task.Status == "cancelling" {
		children, err := service.repository.ActiveChildren(ctx, id)
		if err != nil {
			return err
		}
		for _, childID := range children {
			child, err := service.jobs.Inspect(ctx, childID)
			if err != nil {
				return err
			}
			if jobsdomain.CanCancel(child) {
				if err = coordinator.cancel(ctx, childID); err != nil && !errors.Is(err, jobsdomain.ErrConflict) {
					return err
				}
			}
		}
		_, err = service.repository.ReconcileCommand(ctx, id)
		return err
	}
	if task.Status != "running" {
		return nil
	}
	item, err := service.repository.PendingCommand(ctx, id, time.Now().UTC())
	if errors.Is(err, jobsdomain.ErrNotFound) || errors.Is(err, jobsdomain.ErrFull) {
		return nil
	}
	if err != nil {
		return err
	}
	var request maintenancedomain.BatchOptions
	if err = json.Unmarshal([]byte(parent.OptionsJSON), &request); err != nil {
		return err
	}
	request, err = maintenancedomain.NormalizeOptions(request)
	if err != nil {
		return err
	}
	if !maintenancedomain.IsCommand(request.Operation) || task.TargetType != request.Operation {
		return jobsdomain.ErrValidation
	}
	if service.commands == nil {
		return jobsdomain.ErrStopped
	}
	err = service.repository.CommandPrerequisites(ctx, id, item)
	if err == nil {
		_, err = service.commands.Submit(ctx, request, item, fmt.Sprintf("batch:%s:%d", id, item.Position), service.repository.ChildAdmission(id, item, request.Operation))
	}
	if err == nil {
		coordinator.wake()
		service.WakeCommands()
		return nil
	}
	if maintenancedomain.DispatchWait(err) {
		return service.repository.DispatchResult(ctx, id, item, "TARGET_BUSY", false)
	}
	if code, expected := maintenancedomain.DispatchFailure(err); expected {
		err = service.repository.DispatchResult(ctx, id, item, code, true)
		if err == nil {
			service.WakeCommands()
		}
		return err
	}
	return err
}
func (coordinator *Coordinator) Maintain(ctx context.Context, onError func(error)) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		if err := coordinator.Step(ctx); err != nil && ctx.Err() == nil && onError != nil {
			onError(err)
		}
		select {
		case <-ctx.Done():
			return
		case <-coordinator.service.commandWake:
		case <-ticker.C:
		}
	}
}
