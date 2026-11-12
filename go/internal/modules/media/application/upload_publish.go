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

type UploadPublisher struct {
	sessions   *UploadService
	repository mediaports.UploadPublishRepository
	files      mediaports.UploadPublishStorage
	jobs       *jobsapp.Store
}

func NewUploadPublishing(sessions *UploadService, repository mediaports.UploadPublishRepository, files mediaports.UploadPublishStorage, jobs *jobsapp.Store) *UploadPublisher {
	return &UploadPublisher{sessions: sessions, repository: repository, files: files, jobs: jobs}
}
func (service *UploadPublisher) Submit(ctx context.Context, id string) (jobsdomain.Task, error) {
	if !mediadomain.UploadID(id, "us_") {
		return jobsdomain.Task{}, mediadomain.ErrUploadNotFound
	}
	release, err := service.sessions.lockUpload(ctx, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	defer release()
	session, err := service.sessions.repository.UploadSession(ctx, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	if err = mediadomain.UploadSubmissionAllowed(session, uploadNow()); err != nil {
		return jobsdomain.Task{}, err
	}
	if session.TaskID != nil {
		return service.jobs.Inspect(ctx, *session.TaskID)
	}
	// 受理回调使用同一时刻重验过期，文件列表只携带接收事实和稳定 ID。
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "media_upload", TargetType: "upload_session", TargetID: id, ComicID: session.ComicID, Title: "媒体上传", Payload: "{}"}, "", service.repository.UploadAdmission(session, uploadNow()))
}
func (service *UploadPublisher) Run(ctx context.Context, task jobsdomain.Task) (completion jobsports.Completion, err error) {
	defer func() {
		if err != nil && ctx.Err() == nil {
			code := uploadFailure(err)
			err = jobsdomain.NewFailure(code, err)
		}
	}()
	session, err := service.repository.UploadJob(ctx, task.ID)
	if err != nil {
		return completion, err
	}
	if !mediadomain.UploadIsUnfinished(session.Status) || session.ID != task.TargetID || session.ComicID != task.ComicID {
		return completion, mediadomain.ErrUploadConflict
	}
	if err = service.progress(ctx, task.ID, "processing"); err != nil {
		return completion, err
	}
	if session.Status == "submitted" {
		after := 0
		for {
			files, readErr := service.repository.UploadBatch(ctx, task.ID, after)
			if readErr != nil {
				return completion, readErr
			}
			if len(files) == 0 {
				break
			}
			for _, file := range files {
				after = file.Position
				run, cancel := context.WithTimeout(ctx, 2*time.Hour)
				err = service.publishFile(run, session.ID, task.ID, file)
				cancel()
				if err != nil {
					if ctx.Err() != nil {
						return completion, ctx.Err()
					}
					finish, done := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
					writeErr := service.repository.UploadFileFailure(finish, task.ID, file.FileID, uploadFailure(err))
					done()
					if writeErr != nil {
						return completion, writeErr
					}
					return completion, err
				}
				if err = service.progress(ctx, task.ID, "publishing_files"); err != nil {
					return completion, err
				}
			}
		}
		if err = service.repository.ActivateUpload(ctx, task.ID); err != nil {
			return completion, err
		}
	}
	if session.Status == "activated" {
		// 引用已生效的恢复仍验证新副本，不能仅凭 SQL 状态报告完成。
		after := 0
		for {
			files, readErr := service.repository.UploadBatch(ctx, task.ID, after)
			if readErr != nil {
				return completion, readErr
			}
			if len(files) == 0 {
				break
			}
			for _, file := range files {
				after = file.Position
				run, cancel := context.WithTimeout(ctx, 2*time.Hour)
				verifyErr := service.files.PublishUpload(run, session.ID, file)
				cancel()
				if verifyErr != nil {
					return completion, verifyErr
				}
			}
		}
	}
	if err = service.progress(ctx, task.ID, "retiring"); err != nil {
		return completion, err
	}
	retired, err := service.repository.UploadRetirement(ctx, task.ID)
	if err != nil {
		return completion, err
	}
	if len(retired) > 0 {
		files, err := service.repository.UploadBatch(ctx, task.ID, 0)
		if err != nil || len(files) != 1 {
			if err == nil {
				err = mediadomain.ErrUploadMedia
			}
			return completion, err
		}
		for _, item := range retired {
			run, cancel := context.WithTimeout(ctx, 2*time.Hour)
			outcome, moveErr := service.files.RetireUpload(run, session.ID, files[0], item)
			cancel()
			if moveErr != nil {
				return completion, jobsdomain.NewFailure(jobsdomain.FailureUploadRetire, moveErr)
			}
			if err = service.repository.UploadRetired(ctx, task.ID, item.Kind, outcome); err != nil {
				return completion, err
			}
		}
	}
	if err = service.files.RemoveUploadStage(ctx, session.ID); err != nil {
		return completion, err
	}
	return jobsports.Completion{Persist: service.repository.UploadCompletion(task.ID)}, nil
}
func (service *UploadPublisher) publishFile(ctx context.Context, session, id string, file mediadomain.UploadPublishFile) error {
	if file.Info == nil {
		info, err := service.files.InspectUpload(ctx, session, file)
		if err != nil {
			return err
		}
		if err = service.repository.UploadPrepared(ctx, id, file, info); err != nil {
			return err
		}
		file.Info = &info
	}
	if err := service.files.PublishUpload(ctx, session, file); err != nil {
		return err
	}
	return service.repository.UploadPublished(ctx, id, file.FileID)
}
func (service *UploadPublisher) progress(ctx context.Context, id, stage string) error {
	total, done, err := service.repository.UploadProgress(ctx, id)
	if err != nil {
		return err
	}
	return service.jobs.UpdateProgress(ctx, id, stage, total, done)
}
func (service *UploadPublisher) Items(ctx context.Context, id string, page, size int) (mediadomain.UploadPublishPage, error) {
	if page < 1 || page > 1000000 || size < 1 || size > 100 {
		return mediadomain.UploadPublishPage{}, mediadomain.ErrUploadValidation
	}
	return service.repository.UploadItems(ctx, id, page, size)
}
func uploadFailure(err error) string {
	if code := jobsdomain.CodeOf(err); code != jobsdomain.FailureUnknown {
		return code
	}
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return jobsdomain.FailureUploadTimeout
	case errors.Is(err, mediadomain.ErrUploadChecksum):
		return jobsdomain.FailureUploadChecksum
	case errors.Is(err, mediadomain.ErrUnavailable):
		return jobsdomain.FailureVideoUnavailable
	case errors.Is(err, mediadomain.ErrUploadConflict), errors.Is(err, mediadomain.ErrLQConflict):
		return jobsdomain.FailureUploadConflict
	case errors.Is(err, mediadomain.ErrUploadMedia), errors.Is(err, mediadomain.ErrInvalidVideo), errors.Is(err, mediadomain.ErrProbe):
		return jobsdomain.FailureUploadMedia
	default:
		return jobsdomain.FailureUpload
	}
}
