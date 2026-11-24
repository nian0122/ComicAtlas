package application

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"comicatlas.local/backend/internal/platform/stableid"

	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"syscall"
	"time"
)

type DirectoryMoves struct {
	planner    *DirectoryPlanner
	repository exportports.DirectoryMoveRepository
	files      exportports.DirectoryExecutionFiles
	fence      exportports.DirectoryFileFence
	jobs       *jobsapp.Store
}

func NewDirectoryMoves(planner *DirectoryPlanner, repository exportports.DirectoryMoveRepository, files exportports.DirectoryExecutionFiles, fence exportports.DirectoryFileFence, jobs *jobsapp.Store) *DirectoryMoves {
	return &DirectoryMoves{planner: planner, repository: repository, files: files, fence: fence, jobs: jobs}
}

// Create 受理后即排队；计划在后台执行前冻结，失败重试沿用同一计划。
func (service *DirectoryMoves) Create(ctx context.Context, ids []string, key string) (jobsdomain.Task, error) {
	if err := exportdomain.DirectoryIDs(ids); err != nil {
		return jobsdomain.Task{}, err
	}
	request := exportdomain.DirectoryMoveRequest{ID: directoryMoveID(key, ids), ComicIDs: append([]string(nil), ids...), CreatedAt: time.Now().UTC()}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "directory_move", TargetType: "directory_preview", TargetID: request.ID, Title: "文件夹移出", Payload: "{}"}, key, service.repository.AdmitDirectoryMove(request))
}

func directoryMoveID(key string, ids []string) string {
	if key == "" {
		var token [16]byte
		if _, err := rand.Read(token[:]); err != nil {
			return ""
		}
		return "task_" + hex.EncodeToString(token[:])
	}
	identity := key
	for _, id := range ids {
		identity += "\x00" + id
	}
	return "task_" + stableid.Hex(identity)
}

func (service *DirectoryMoves) Run(parent context.Context, task jobsdomain.Task) (jobsports.Completion, error) {
	if task.Type != "directory_move" || task.ComicID != "" || task.TargetType != "directory_preview" || task.Status != "running" {
		return jobsports.Completion{}, exportdomain.ErrManifest
	}
	request, err := service.repository.LoadDirectoryMoveRequest(parent, task.ID, task.TargetID)
	if err != nil {
		return jobsports.Completion{}, directoryMoveFailure(task.ID, "load", err)
	}
	run, err := service.repository.LoadDirectoryTaskRun(parent, task.ID)
	if errors.Is(err, exportdomain.ErrNotFound) {
		run, err = service.plan(parent, task, request)
	}
	if err != nil {
		return jobsports.Completion{}, directoryMoveFailure(task.ID, "plan", err)
	}
	var report exportdomain.DirectoryReport
	return jobsports.Completion{
		BeforePersist: func(parent context.Context) (release func(), err error) {
			ctx, cancel := context.WithTimeout(parent, 2*time.Hour)
			unlock, err := service.fence.AcquireFileMutation(ctx)
			if err != nil {
				cancel()
				return nil, directoryMoveFailure(task.ID, "fence", err)
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
						err = directoryMoveFailure(task.ID, "execute", err)
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
			slog.Info("文件夹移出阶段统计", "taskId", task.ID, "renames", report.Stats.Renames, "directoryMoves", report.Stats.DirectoryMoves, "resultBatches", report.Stats.ResultBatches, "sceneMs", report.Stats.SceneMillis, "moveMs", report.Stats.MoveMillis, "persistMs", report.Stats.PersistMillis, "publishMs", report.Stats.PublishMillis)
			return release, nil
		},
		Persist: service.repository.CompleteDirectoryRun(run, &report),
	}, nil
}

func (service *DirectoryMoves) plan(parent context.Context, task jobsdomain.Task, request exportdomain.DirectoryMoveRequest) (exportdomain.DirectoryTaskRun, error) {
	ctx, cancel := context.WithTimeout(parent, 30*time.Minute)
	defer cancel()
	release, err := service.fence.AcquireFileMutation(ctx)
	if err != nil {
		return exportdomain.DirectoryTaskRun{}, err
	}
	defer release()
	planning, err := service.planner.PlanDirectory(ctx, task.TargetID, request.ComicIDs)
	if err != nil {
		return exportdomain.DirectoryTaskRun{}, err
	}
	return service.repository.SaveDirectoryMovePlan(ctx, task.ID, request, planning)
}

func (service *DirectoryMoves) Retry(ctx context.Context, id string) error {
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
	return service.jobs.RetryGuarded(ctx, id, service.repository.GuardDirectoryRetry(id))
}

func (service *DirectoryMoves) Results(ctx context.Context, id string, page, size int) (exportdomain.DirectoryResultPage, error) {
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

func directoryMoveFailure(taskID, stage string, err error) error {
	code := jobsdomain.FailureDirectoryMove
	switch {
	case errors.Is(err, exportdomain.ErrManifest):
		code = jobsdomain.FailureManifest
	case errors.Is(err, exportdomain.ErrSource):
		code = jobsdomain.FailureDirectoryMoveSource
	case errors.Is(err, exportdomain.ErrConflict):
		code = jobsdomain.FailureDirectoryMoveConflict
	case errors.Is(err, exportdomain.ErrStorage):
		code = jobsdomain.FailureDirectoryMoveStorage
	case errors.Is(err, os.ErrNotExist):
		code = jobsdomain.FailureDirectoryMoveSource
	case directoryStorageError(err):
		code = jobsdomain.FailureDirectoryMoveStorage
	case errors.Is(err, context.DeadlineExceeded):
		code = jobsdomain.FailureDirectoryMoveTimeout
	}
	attrs := []any{"taskId", taskID, "stage", stage, "errorType", fmt.Sprintf("%T", err)}
	var pathErr *os.PathError
	if errors.As(err, &pathErr) {
		attrs = append(attrs, "op", pathErr.Op)
		var errno syscall.Errno
		if errors.As(pathErr.Err, &errno) {
			attrs = append(attrs, "errno", uintptr(errno))
		}
	}
	slog.Warn("文件夹移出失败", attrs...)
	return jobsdomain.NewFailure(code, err)
}

func directoryStorageError(err error) bool {
	var pathErr *os.PathError
	if errors.As(err, &pathErr) {
		return !errors.Is(err, os.ErrNotExist)
	}
	var errno syscall.Errno
	return errors.As(err, &errno)
}
