package application

import (
	"context"
	"errors"
	"time"

	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

type TranscodeService struct {
	repository mediaports.TranscodeRepository
	storage    mediaports.TranscodeStorage
	jobs       *jobsapp.Store
}

func NewTranscode(repository mediaports.TranscodeRepository, storage mediaports.TranscodeStorage, jobs *jobsapp.Store) *TranscodeService {
	return &TranscodeService{repository: repository, storage: storage, jobs: jobs}
}
func (service *TranscodeService) Submit(ctx context.Context, kind, id, key string) (jobsdomain.Task, error) {
	return service.SubmitGuarded(ctx, kind, id, key, nil)
}
func (service *TranscodeService) SubmitGuarded(ctx context.Context, kind, id, key string, guard jobsports.Admission) (jobsdomain.Task, error) {
	if err := mediadomain.ValidateTranscodeScope(kind, id); err != nil {
		return jobsdomain.Task{}, err
	}
	scope, err := service.repository.TranscodeScope(ctx, kind, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "video_transcode", TargetType: kind, TargetID: id, ComicID: scope.ComicID, Title: scope.Title, Payload: "{}"}, key, jobsports.CombineAdmissions(guard, service.repository.TranscodeAdmission(scope)))
}
func (service *TranscodeService) Run(ctx context.Context, task jobsdomain.Task) (jobsports.Completion, error) {
	if err := service.repository.TranscodeBegin(ctx, task.ID); err != nil {
		return jobsports.Completion{}, err
	}
	if err := service.transcodeProgress(ctx, task.ID); err != nil {
		return jobsports.Completion{}, err
	}
	if err := service.collectVideo(ctx, task.ID); err != nil {
		return jobsports.Completion{}, jobsdomain.NewFailure(jobsdomain.FailureTranscodeCleanup, err)
	}
	after := 0
	for {
		items, err := service.repository.TranscodeBatch(ctx, task.ID, after)
		if err != nil {
			return jobsports.Completion{}, err
		}
		if len(items) == 0 {
			break
		}
		for _, item := range items {
			after = item.Position
			if err = service.transcodeItem(ctx, task.ID, item); err != nil {
				if ctx.Err() != nil {
					return jobsports.Completion{}, ctx.Err()
				}
				code := transcodeFailure(err)
				finish, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
				writeErr := service.repository.TranscodeFail(finish, task.ID, item, code)
				cancel()
				if writeErr != nil {
					return jobsports.Completion{}, writeErr
				}
				return jobsports.Completion{}, jobsdomain.NewFailure(code, err)
			}
			if err = service.transcodeProgress(ctx, task.ID); err != nil {
				return jobsports.Completion{}, err
			}
			if err = service.collectVideo(ctx, task.ID); err != nil {
				return jobsports.Completion{}, jobsdomain.NewFailure(jobsdomain.FailureTranscodeCleanup, err)
			}
		}
	}
	if err := service.transcodeProgress(ctx, task.ID); err != nil {
		return jobsports.Completion{}, err
	}
	return jobsports.Completion{Persist: service.repository.TranscodeCompletion(task.ID)}, nil
}
func (service *TranscodeService) transcodeItem(ctx context.Context, id string, item mediadomain.TranscodeItem) error {
	run, cancel := context.WithTimeout(ctx, mediadomain.MaxTranscodeTaskTimeout)
	defer cancel()
	ctx = run
	if item.Status == "pending" {
		record, err := service.storage.EncodeVideo(ctx, id, item)
		if err != nil {
			return err
		}
		if record == nil {
			return service.repository.TranscodeSkip(ctx, id, item)
		}
		if err = service.repository.TranscodePrepare(ctx, id, item, *record); err != nil {
			return err
		}
		item.Output = record
	}
	if err := service.storage.PublishVideo(ctx, id, item); err != nil {
		return err
	}
	// 该事务只切换引用，不执行编码或文件 IO；断在任一侧都复用 prepared 检查点。
	return service.repository.TranscodeCommit(ctx, id, item)
}
func (service *TranscodeService) collectVideo(ctx context.Context, id string) error {
	for {
		items, err := service.repository.TranscodeGarbage(ctx, id)
		if err != nil {
			return err
		}
		if len(items) == 0 {
			return nil
		}
		for _, item := range items {
			run, cancel := context.WithTimeout(ctx, 2*time.Hour)
			err = service.storage.CollectVideo(run, id, item)
			cancel()
			if err != nil {
				return err
			}
			if err = service.repository.TranscodeCollected(ctx, id, item.MediaID); err != nil {
				return err
			}
		}
	}
}
func (service *TranscodeService) transcodeProgress(ctx context.Context, id string) error {
	total, done, err := service.repository.TranscodeProgress(ctx, id)
	if err != nil {
		return err
	}
	return service.jobs.UpdateProgress(ctx, id, "processing", total, done)
}
func (service *TranscodeService) Items(ctx context.Context, id string, page, size int) (mediadomain.TranscodePage, error) {
	if page < 1 || page > 1000000 || size < 1 || size > 100 {
		return mediadomain.TranscodePage{}, mediadomain.ErrLQValidation
	}
	return service.repository.TranscodeItems(ctx, id, page, size)
}
func transcodeFailure(err error) string {
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return jobsdomain.FailureTranscodeTimeout
	case errors.Is(err, mediadomain.ErrTranscodeUnavailable), errors.Is(err, mediadomain.ErrUnavailable):
		return jobsdomain.FailureTranscodeUnavailable
	case errors.Is(err, mediadomain.ErrTranscodeLimit):
		return jobsdomain.FailureTranscodeLimit
	case errors.Is(err, mediadomain.ErrTranscodeChanged), errors.Is(err, mediadomain.ErrLQConflict), errors.Is(err, mediadomain.ErrImageChanged):
		return jobsdomain.FailureTranscodeSource
	default:
		return jobsdomain.FailureTranscode
	}
}
