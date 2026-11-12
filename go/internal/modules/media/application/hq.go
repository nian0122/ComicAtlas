package application

import (
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"errors"
	"time"
)

type HQService struct {
	repository mediaports.HQRepository
	storage    mediaports.HQStorage
	jobs       *jobsapp.Store
}

func NewHQ(repository mediaports.HQRepository, storage mediaports.HQStorage, jobs *jobsapp.Store) *HQService {
	return &HQService{repository: repository, storage: storage, jobs: jobs}
}
func (service *HQService) Preview(ctx context.Context, kind, id string) (mediadomain.HQReleasePreview, error) {
	if err := mediadomain.ValidateLQScope(kind, id); err != nil {
		return mediadomain.HQReleasePreview{}, err
	}
	scope, err := service.repository.Scope(ctx, kind, id)
	if err != nil {
		return mediadomain.HQReleasePreview{}, err
	}
	return service.repository.HQPreview(ctx, scope)
}
func (service *HQService) Submit(ctx context.Context, kind, id, key string) (jobsdomain.Task, error) {
	return service.SubmitGuarded(ctx, kind, id, key, nil)
}
func (service *HQService) SubmitGuarded(ctx context.Context, kind, id, key string, guard jobsports.Admission) (jobsdomain.Task, error) {
	if err := mediadomain.ValidateLQScope(kind, id); err != nil {
		return jobsdomain.Task{}, err
	}
	scope, err := service.repository.Scope(ctx, kind, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "hq_release", TargetType: kind, TargetID: id, ComicID: scope.ComicID, Title: scope.Title, Payload: "{}"}, key, jobsports.CombineAdmissions(guard, service.repository.HQAdmission(scope)))
}
func (service *HQService) Run(ctx context.Context, task jobsdomain.Task) (jobsports.Completion, error) {
	run, cancel := context.WithTimeout(ctx, 30*time.Minute)
	defer cancel()
	if err := service.repository.HQBegin(run, task.ID); err != nil {
		return jobsports.Completion{}, err
	}
	if err := service.progressHQ(run, task.ID, "preflight"); err != nil {
		return jobsports.Completion{}, err
	}
	// 全范围 LQ 与原件存在性预检完成后，才允许首项产生删除副作用。
	for _, preflight := range []bool{true, false} {
		after := 0
		for {
			items, err := service.repository.HQBatch(run, task.ID, after)
			if err != nil {
				return jobsports.Completion{}, err
			}
			if len(items) == 0 {
				break
			}
			for _, item := range items {
				after = item.Position
				current, err := service.repository.HQCurrent(run, item.MediaID)
				if err == nil && !mediadomain.MatchHQSnapshot(item, current) {
					err = mediadomain.ErrHQPrerequisite
				}
				if err == nil && preflight {
					valid, verifyErr := service.storage.Verify(run, item.LQ)
					err = verifyErr
					if err == nil && !valid {
						err = mediadomain.ErrHQPrerequisite
					}
				}
				if err == nil && preflight && item.Status != "prepared" {
					err = service.storage.VerifyHQ(run, item)
				}
				if err == nil && !preflight {
					err = service.repository.HQPrepare(run, task.ID, item)
					if err == nil {
						var outcome string
						outcome, err = service.storage.ReleaseHQ(run, task.ID, item)
						if err == nil {
							finish, done := context.WithTimeout(context.WithoutCancel(run), 5*time.Second)
							err = service.repository.HQRecord(finish, task.ID, item, outcome)
							done()
						}
					}
				}
				if err != nil {
					if ctx.Err() != nil {
						return jobsports.Completion{}, ctx.Err()
					}
					code := jobsdomain.FailureHQRelease
					if errors.Is(err, mediadomain.ErrHQPrerequisite) || errors.Is(err, mediadomain.ErrLQConflict) || errors.Is(err, mediadomain.ErrImageChanged) || errors.Is(err, mediadomain.ErrLQNotFound) {
						code = jobsdomain.FailureHQPrerequisite
					}
					if errors.Is(err, context.DeadlineExceeded) {
						code = jobsdomain.FailureHQTimeout
					}
					finish, done := context.WithTimeout(context.WithoutCancel(run), 5*time.Second)
					writeErr := service.repository.HQFail(finish, task.ID, item, code)
					done()
					if writeErr != nil {
						return jobsports.Completion{}, writeErr
					}
					return jobsports.Completion{}, jobsdomain.NewFailure(code, err)
				}
				if !preflight {
					if err := service.progressHQ(run, task.ID, "releasing"); err != nil {
						return jobsports.Completion{}, err
					}
				}
			}
		}
	}
	return jobsports.Completion{Persist: service.repository.HQCompletion(task.ID)}, nil
}
func (service *HQService) progressHQ(ctx context.Context, id, stage string) error {
	total, done, err := service.repository.HQProgress(ctx, id)
	if err != nil {
		return err
	}
	return service.jobs.UpdateProgress(ctx, id, stage, total, done)
}
func (service *HQService) Items(ctx context.Context, id string, page, size int) (mediadomain.HQReleasePage, error) {
	if page < 1 || page > 1000000 || size < 1 || size > 100 {
		return mediadomain.HQReleasePage{}, mediadomain.ErrLQValidation
	}
	return service.repository.HQItems(ctx, id, page, size)
}
