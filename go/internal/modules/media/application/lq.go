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
	"time"
)

type LQService struct {
	repository mediaports.LQRepository
	storage    mediaports.LQStorage
	jobs       *jobsapp.Store
}

func NewLQ(repository mediaports.LQRepository, storage mediaports.LQStorage, jobs *jobsapp.Store) *LQService {
	return &LQService{repository: repository, storage: storage, jobs: jobs}
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
	after := 0
	for {
		items, err := service.repository.Batch(ctx, task.ID, after)
		if err != nil {
			return jobsports.Completion{}, err
		}
		if len(items) == 0 {
			break
		}
		for _, item := range items {
			if err = ctx.Err(); err != nil {
				return jobsports.Completion{}, err
			}
			after = item.Position
			code, processErr := service.process(ctx, task.ID, item, request)
			if processErr != nil {
				return jobsports.Completion{}, processErr
			}
			if err = service.progress(ctx, task.ID); err != nil {
				return jobsports.Completion{}, err
			}
			service.collect(ctx)
			if code == jobsdomain.FailureImageToolUnavailable {
				return jobsports.Completion{}, jobsdomain.NewFailure(code, nil)
			}
		}
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

func (service *LQService) process(ctx context.Context, id string, item mediadomain.LQItem, request mediadomain.LQRequest) (string, error) {
	record, source, err := service.repository.Current(ctx, item.MediaID)
	if err != nil && !errors.Is(err, mediadomain.ErrLQNotFound) {
		return "", err
	}
	if err == nil && source != item.SourceSHA256 {
		err = mediadomain.ErrImageChanged
	}
	if err == nil && record != nil && record.Valid {
		valid, verifyErr := service.storage.Verify(ctx, *record)
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
	record, _, err := service.repository.Current(ctx, id)
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
