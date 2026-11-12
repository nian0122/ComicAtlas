package application

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"context"
	"errors"
	"log/slog"
	"time"
)

type DirectoryExecution struct {
	repository exportports.DirectoryExecutionRepository
	files      exportports.DirectoryExecutionFiles
	fence      exportports.DirectoryFileFence
	jobs       *jobsapp.Store
}

func NewDirectoryExecution(repository exportports.DirectoryExecutionRepository, files exportports.DirectoryExecutionFiles, fence exportports.DirectoryFileFence, jobs *jobsapp.Store) *DirectoryExecution {
	return &DirectoryExecution{repository: repository, files: files, fence: fence, jobs: jobs}
}

func (service *DirectoryExecution) Retry(ctx context.Context, id string) error {
	if !exportdomain.TaskID(id) {
		return exportdomain.ErrValidation
	}
	task, err := service.jobs.Inspect(ctx, id)
	if err != nil {
		return err
	}
	if task.Type != "directory_move" || !jobsdomain.CanRetry(task.Status) {
		return exportdomain.ErrConflict
	}
	// 受理不等待文件互斥、不读取大计划；实际复核仍在 Run 的任何文件动作之前。
	return service.jobs.RetryGuarded(ctx, id, service.repository.GuardDirectoryRetry(id))
}

func (service *DirectoryExecution) Results(ctx context.Context, id string, page, size int) (exportdomain.DirectoryResultPage, error) {
	if err := exportdomain.ValidateDirectoryResultQuery(id, page, size); err != nil {
		return exportdomain.DirectoryResultPage{}, err
	}
	value, err := service.repository.DirectoryResults(ctx, id, page, size)
	if err == nil && value.Complete && value.Status == "succeeded" {
		directory := "exports/" + id
		value.Directory = &directory
	}
	return value, err
}

// Run 只装配一次完成动作；文件执行、最终复核及数据库收尾共用一个连续锁窗口。
func (service *DirectoryExecution) Run(parent context.Context, task jobsdomain.Task) (jobsports.Completion, error) {
	if task.Type != "directory_move" || task.ComicID != "" || task.TargetType != "directory_preview" || task.Status != "running" {
		return jobsports.Completion{}, exportdomain.ErrManifest
	}
	run, err := service.repository.LoadDirectoryRun(parent, task.ID)
	if err != nil {
		return jobsports.Completion{}, directoryExecutionFailure(err)
	}
	if run.PreviewID != task.TargetID {
		return jobsports.Completion{}, exportdomain.ErrManifest
	}
	var report exportdomain.DirectoryReport
	return jobsports.Completion{
		BeforePersist: func(parent context.Context) (release func(), err error) {
			ctx, cancel := context.WithTimeout(parent, 2*time.Hour)
			unlock, err := service.fence.AcquireFileMutation(ctx)
			if err != nil {
				cancel()
				return nil, directoryExecutionFailure(err)
			}
			release = func() { unlock(); cancel() }
			defer func() {
				if recover() != nil {
					err = jobsdomain.NewFailure(jobsdomain.FailureInternal, nil)
				}
				if err != nil {
					release()
					release = nil
					if jobsdomain.CodeOf(err) == jobsdomain.FailureUnknown {
						err = directoryExecutionFailure(err)
					}
				}
			}()
			if err = service.repository.StartDirectoryRun(ctx, run); err != nil {
				return release, err
			}
			report, err = service.files.ExecuteDirectory(ctx, task.ID, run.Planning, func(rows []exportdomain.DirectoryFileResult) error {
				return service.repository.SaveDirectoryResults(ctx, task.ID, rows)
			})
			if err != nil {
				return release, err
			}
			if err = service.jobs.UpdateProgress(ctx, task.ID, "publishing", report.FileCount, report.FileCount); err != nil {
				return release, err
			}
			slog.Info("文件夹移出阶段统计", "taskId", task.ID, "hashFiles", report.Stats.HashFiles, "hashBytes", report.Stats.HashBytes, "renames", report.Stats.Renames, "directoryMoves", report.Stats.DirectoryMoves, "resultBatches", report.Stats.ResultBatches, "sceneMs", report.Stats.SceneMillis, "contentMs", report.Stats.ContentMillis, "moveMs", report.Stats.MoveMillis, "persistMs", report.Stats.PersistMillis, "publishMs", report.Stats.PublishMillis)
			return release, nil
		},
		Persist: service.repository.CompleteDirectoryRun(run, &report),
	}, nil
}

func directoryExecutionFailure(err error) error {
	code := jobsdomain.FailureDirectoryMove
	switch {
	case errors.Is(err, exportdomain.ErrManifest):
		code = jobsdomain.FailureManifest
	case errors.Is(err, exportdomain.ErrSource):
		code = jobsdomain.FailureDirectoryMoveSource
	case errors.Is(err, exportdomain.ErrConflict):
		code = jobsdomain.FailureDirectoryMoveConflict
	case errors.Is(err, context.DeadlineExceeded):
		code = jobsdomain.FailureDirectoryMoveTimeout
	}
	return jobsdomain.NewFailure(code, err)
}
