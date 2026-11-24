package application

import (
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"encoding/json"
	"errors"
	"os"
	"sync"
	"time"
)

type LQService struct {
	repository mediaports.LQRepository
	storage    mediaports.LQStorage
	jobs       *jobsapp.Store
	workers    int
}

func NewLQ(repository mediaports.LQRepository, storage mediaports.LQStorage, jobs *jobsapp.Store, workers int) *LQService {
	if workers < 1 {
		workers = 1
	}
	return &LQService{repository: repository, storage: storage, jobs: jobs, workers: workers}
}

func (service *LQService) Submit(ctx context.Context, kind, id string, request mediadomain.LQRequest, key string) (jobsdomain.Task, error) {
	return service.SubmitGuarded(ctx, kind, id, request, key, nil)
}
func (service *LQService) SubmitGuarded(ctx context.Context, kind, id string, request mediadomain.LQRequest, key string, guard jobsports.Admission) (jobsdomain.Task, error) {
	if err := mediadomain.ValidateLQScope(kind, id); err != nil {
		return jobsdomain.Task{}, err
	}
	if request.Spec == (mediadomain.ImageSpec{}) {
		request.Spec = mediadomain.DefaultImageSpec()
	}
	if err := mediadomain.ValidateImageSpec(request.Spec); err != nil {
		return jobsdomain.Task{}, mediadomain.ErrLQValidation
	}
	scope, err := service.repository.Scope(ctx, kind, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	data, err := json.Marshal(request)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "image_lq", TargetType: kind, TargetID: id, ComicID: scope.ComicID, Title: scope.Title, Payload: string(data)}, key, jobsports.CombineAdmissions(guard, service.repository.Admission(scope)))
}

// Run 由单个分派协程按批读取待处理项，固定数量的工作协程并行处理。
// 进度最多每 500 ms 从数据库重算一次，避免逐张写进度与工作协程互相覆盖。
func (service *LQService) Run(ctx context.Context, task jobsdomain.Task) (jobsports.Completion, error) {
	var request mediadomain.LQRequest
	if task.RequestJSON == nil || json.Unmarshal([]byte(*task.RequestJSON), &request) != nil || mediadomain.ValidateImageSpec(request.Spec) != nil {
		return jobsports.Completion{}, jobsdomain.NewFailure(jobsdomain.FailureInternal, nil)
	}
	if err := service.repository.Begin(ctx, task.ID); err != nil {
		return jobsports.Completion{}, err
	}
	if err := service.progress(ctx, task.ID); err != nil {
		return jobsports.Completion{}, err
	}
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	items := make(chan mediadomain.LQItem)
	var wait sync.WaitGroup
	var failureOnce sync.Once
	var failure error
	fail := func(err error) {
		if err == nil {
			return
		}
		failureOnce.Do(func() {
			failure = err
			cancel()
		})
	}
	wait.Add(1)
	go func() {
		defer wait.Done()
		if err := service.dispatch(runCtx, task.ID, items); err != nil {
			fail(err)
		}
	}()
	for range service.workers {
		wait.Add(1)
		go func() {
			defer wait.Done()
			if err := service.work(runCtx, task.ID, items, request); err != nil {
				fail(err)
			}
		}()
	}
	stopProgress := make(chan struct{})
	progressDone := make(chan struct{})
	go func() {
		defer close(progressDone)
		ticker := time.NewTicker(500 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-stopProgress:
				return
			case <-ticker.C:
				_ = service.progress(ctx, task.ID)
			}
		}
	}()
	wait.Wait()
	close(stopProgress)
	<-progressDone
	cancel()
	if failure != nil {
		return jobsports.Completion{}, failure
	}
	if ctx.Err() == nil {
		_ = service.progress(ctx, task.ID)
	}
	progress, err := service.repository.Progress(ctx, task.ID)
	if err != nil {
		return jobsports.Completion{}, err
	}
	if progress.Failed > 0 {
		return jobsports.Completion{}, jobsdomain.NewFailure(progress.FirstError, nil)
	}
	return jobsports.Completion{Persist: service.repository.Completion(task.ID)}, nil
}

func (service *LQService) dispatch(ctx context.Context, id string, items chan<- mediadomain.LQItem) error {
	defer close(items)
	after := 0
	for {
		batch, err := service.repository.Batch(ctx, id, after)
		if err != nil {
			return err
		}
		if len(batch) == 0 {
			return nil
		}
		for _, item := range batch {
			if err = ctx.Err(); err != nil {
				return err
			}
			after = item.Position
			select {
			case items <- item:
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		service.collect(ctx)
	}
}

func (service *LQService) work(ctx context.Context, id string, items <-chan mediadomain.LQItem, request mediadomain.LQRequest) error {
	for item := range items {
		if err := ctx.Err(); err != nil {
			return err
		}
		code, err := service.process(ctx, id, item, request)
		if err != nil {
			return err
		}
		if code == jobsdomain.FailureImageToolUnavailable {
			return jobsdomain.NewFailure(code, nil)
		}
	}
	return nil
}

func (service *LQService) process(ctx context.Context, id string, item mediadomain.LQItem, request mediadomain.LQRequest) (string, error) {
	record, err := service.repository.Current(ctx, item.MediaID)
	if err != nil && !errors.Is(err, mediadomain.ErrLQNotFound) {
		return "", err
	}
	if err == nil && record != nil && record.Valid {
		valid, verifyErr := service.storage.Intact(ctx, *record)
		if verifyErr != nil {
			return "", verifyErr
		}
		if valid && !request.Force {
			item.Status = "skipped"
			return "", service.repository.Record(ctx, id, item, record, "")
		}
		if !valid {
			if err = service.repository.Invalidate(ctx, *record); err != nil {
				return "", err
			}
		}
	}
	var generated mediadomain.LQRecord
	if err == nil {
		generated, err = service.storage.Generate(ctx, id, item, request.Spec)
	}
	if ctx.Err() != nil {
		return "", ctx.Err()
	}
	if errors.Is(err, context.Canceled) {
		return "", err
	}
	if errors.Is(err, context.DeadlineExceeded) {
		item.Status = "failed"
		return jobsdomain.FailureImageTimeout, service.repository.Record(ctx, id, item, nil, jobsdomain.FailureImageTimeout)
	}
	if err == nil {
		item.Status = "generated"
		finish, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		err = service.repository.Record(finish, id, item, &generated, "")
		cancel()
		if err == nil {
			return "", nil
		}
		_ = service.storage.Remove(generated.Path)
		if !errors.Is(err, mediadomain.ErrLQConflict) {
			return "", err
		}
	}
	code := lqFailureCode(err)
	item.Status = "failed"
	return code, service.repository.Record(ctx, id, item, nil, code)
}
func lqFailureCode(err error) string {
	switch {
	case errors.Is(err, mediadomain.ErrLQNotFound):
		return jobsdomain.FailureSourceMissing
	case errors.Is(err, mediadomain.ErrImageChanged), errors.Is(err, mediadomain.ErrLQConflict):
		return jobsdomain.FailureSourceChanged
	case errors.Is(err, mediadomain.ErrImageUnavailable):
		return jobsdomain.FailureImageToolUnavailable
	case errors.Is(err, mediadomain.ErrImageLimit):
		return jobsdomain.FailureImageBudget
	case errors.Is(err, mediadomain.ErrImageInvalid):
		return jobsdomain.FailureImage
	case errors.Is(err, mediadomain.ErrImageIO):
		return jobsdomain.FailureWrite
	default:
		return jobsdomain.FailureImageRender
	}
}
func (service *LQService) progress(ctx context.Context, id string) error {
	progress, err := service.repository.Progress(ctx, id)
	if err != nil {
		return err
	}
	return service.jobs.UpdateProgress(ctx, id, "processing", progress.Total, progress.Completed)
}
func (service *LQService) collect(ctx context.Context) {
	paths, err := service.repository.Garbage(ctx)
	if err != nil {
		return
	}
	for _, path := range paths {
		if ctx.Err() != nil {
			return
		}
		if service.storage.Remove(path) == nil {
			_ = service.repository.Collected(ctx, path)
		}
	}
}
func (service *LQService) Items(ctx context.Context, id string, page, size int) (mediadomain.LQItemPage, error) {
	if page < 1 || page > 1000000 || size < 1 || size > 100 {
		return mediadomain.LQItemPage{}, mediadomain.ErrLQValidation
	}
	return service.repository.Items(ctx, id, page, size)
}
func (service *LQService) Open(ctx context.Context, id string) (*os.File, error) {
	record, err := service.repository.Current(ctx, id)
	if err != nil {
		return nil, err
	}
	if record == nil || !record.Valid {
		return nil, mediadomain.ErrLQNotFound
	}
	file, err := service.storage.Open(*record)
	if err != nil {
		_ = service.repository.Invalidate(ctx, *record)
		return nil, mediadomain.ErrLQNotFound
	}
	return file, nil
}
