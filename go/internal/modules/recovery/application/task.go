package application

import (
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	recoveryports "comicatlas.local/backend/internal/modules/recovery/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"errors"
	"time"
)

type Service struct {
	repository recoveryports.TaskRepository
	discovery  *DiscoveryService
	books      *BookService
}

func New(repository recoveryports.TaskRepository, sources recoveryports.TaskSources, jobs *jobsapp.Store) *Service {
	return &Service{repository: repository, discovery: NewDiscoveryService(repository, sources, jobs), books: NewBookService(repository, sources)}
}

func (service *Service) Submit(ctx context.Context, key string) (jobsdomain.Task, error) {
	return service.discovery.Create(ctx, key)
}

func (service *Service) Run(parent context.Context, task jobsdomain.Task) (completion jobsports.Completion, err error) {
	defer func() {
		if err != nil && parent.Err() == nil && jobsdomain.CodeOf(err) == jobsdomain.FailureUnknown {
			code := jobsdomain.FailureRecovery
			if errors.Is(err, context.DeadlineExceeded) {
				code = jobsdomain.FailureRecoveryTimeout
			}
			err = jobsdomain.NewFailure(code, err)
		}
	}()
	if task.Type != "disk_recovery" || task.Status != "running" || task.Attempt < 1 {
		return completion, recoverydomain.ErrValidation
	}
	ctx, cancel := context.WithTimeout(parent, 2*time.Hour)
	defer cancel()
	run, err := service.discovery.Discover(ctx, task.ID)
	if err != nil {
		return completion, err
	}
	if run.Attempt != task.Attempt {
		return completion, recoverydomain.ErrConflict
	}
	after := ""
	for {
		ids, readErr := service.repository.PendingCandidates(ctx, task.ID, after, recoverydomain.DiscoveryBatchSize)
		if readErr != nil {
			return completion, readErr
		}
		if len(ids) == 0 {
			break
		}
		for _, id := range ids {
			if _, err = service.books.Process(ctx, task, id); err != nil {
				return completion, err
			}
			after = id
		}
	}
	summary, err := service.repository.Summary(ctx, task.ID)
	if err != nil {
		return completion, err
	}
	if summary.Failed > 0 {
		return completion, jobsdomain.NewFailure(jobsdomain.FailureRecoveryPartial, nil)
	}
	return jobsports.Completion{Persist: service.repository.RecoveryCompletion(task.ID, task.Attempt)}, nil
}

func (service *Service) Results(ctx context.Context, task, after string, limit int) (recoverydomain.ResultPage, error) {
	if err := recoverydomain.ValidateDiscoveryPage(task, after, limit); err != nil {
		return recoverydomain.ResultPage{}, err
	}
	return service.repository.ResultPage(ctx, task, after, limit)
}

func (service *Service) Placeholder(ctx context.Context, comic string) (recoverydomain.Placeholder, error) {
	return service.repository.Placeholder(ctx, comic)
}
