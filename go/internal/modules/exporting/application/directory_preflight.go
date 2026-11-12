package application

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"context"
	"crypto/rand"
	"encoding/hex"
	"time"
)

type DirectoryPreflight struct {
	planner    *DirectoryPlanner
	repository exportports.DirectoryPreflightRepository
	fence      exportports.DirectoryFileFence
	jobs       *jobsapp.Store
}

func NewDirectoryPreflight(planner *DirectoryPlanner, repository exportports.DirectoryPreflightRepository, fence exportports.DirectoryFileFence, jobs *jobsapp.Store) *DirectoryPreflight {
	return &DirectoryPreflight{planner: planner, repository: repository, fence: fence, jobs: jobs}
}

func (service *DirectoryPreflight) Create(ctx context.Context, ids []string) (exportdomain.DirectoryPreflightView, error) {
	var view exportdomain.DirectoryPreflightView
	if err := exportdomain.DirectoryIDs(ids); err != nil {
		return view, err
	}
	var identity [16]byte
	var secret [32]byte
	if _, err := rand.Read(identity[:]); err != nil {
		return view, err
	}
	if _, err := rand.Read(secret[:]); err != nil {
		return view, err
	}
	id, token := "task_"+hex.EncodeToString(identity[:]), hex.EncodeToString(secret[:])
	hash, _ := exportdomain.DirectoryConfirmationHash(token)
	request := exportdomain.DirectoryPreflightRequest{ID: id, TokenHash: hash, ComicIDs: append([]string(nil), ids...), CreatedAt: time.Now().UTC()}
	_, err := service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "directory_preflight", TargetType: "directory_preview", TargetID: id, Title: "文件夹移出预检", Payload: "{}"}, "", service.repository.AdmitDirectoryPreflight(request))
	if err != nil {
		return view, err
	}
	// 只查询已持久化的轻量状态；后台即使已经完成，也不等待或重读完整计划。
	view, err = service.Status(ctx, id)
	if err == nil {
		view.Confirmation = token
	}
	return view, err
}

func (service *DirectoryPreflight) Status(ctx context.Context, id string) (exportdomain.DirectoryPreflightView, error) {
	if !exportdomain.TaskID(id) {
		return exportdomain.DirectoryPreflightView{}, exportdomain.ErrValidation
	}
	view, err := service.repository.DirectoryPreflightStatus(ctx, id)
	if err == nil {
		view.Ready = view.Status == "succeeded" && view.MoveTaskID == "" && view.ExpiresAt != nil && time.Now().UTC().Before(*view.ExpiresAt)
	}
	return view, err
}

func (service *DirectoryPreflight) Retry(ctx context.Context, id string) error {
	if !exportdomain.TaskID(id) {
		return exportdomain.ErrValidation
	}
	task, err := service.jobs.Inspect(ctx, id)
	if err != nil {
		return err
	}
	if task.Type != "directory_preflight" || !jobsdomain.CanRetry(task.Status) {
		return exportdomain.ErrConflict
	}
	return service.jobs.RetryGuarded(ctx, id, service.repository.RetryDirectoryPreflight(id))
}

func (service *DirectoryPreflight) Run(parent context.Context, task jobsdomain.Task) (completion jobsports.Completion, err error) {
	defer func() {
		if err != nil && parent.Err() == nil {
			err = jobsdomain.NewFailure(jobsdomain.FailureDirectoryPreflight, err)
		}
	}()
	if task.Type != "directory_preflight" || task.ComicID != "" || task.TargetType != "directory_preview" || task.RequestJSON == nil || *task.RequestJSON != "{}" {
		return completion, exportdomain.ErrManifest
	}
	ctx, cancel := context.WithTimeout(parent, 30*time.Minute)
	defer cancel()
	request, err := service.repository.LoadDirectoryPreflight(ctx, task.ID, task.TargetID)
	if err != nil {
		return completion, err
	}
	if err = service.jobs.UpdateProgress(ctx, task.ID, "inspecting", len(request.ComicIDs), 0); err != nil {
		return completion, err
	}
	release, err := service.fence.AcquireFileMutation(ctx)
	if err != nil {
		return completion, err
	}
	defer release()
	planning, err := service.planner.PlanDirectory(ctx, request.ID, request.ComicIDs)
	if err != nil {
		return completion, err
	}
	data, stamp, err := exportdomain.EncodeDirectoryPlanning(planning)
	if err != nil {
		return completion, err
	}
	now := time.Now().UTC()
	record := exportdomain.DirectoryPreviewRecord{ID: request.ID, TokenHash: request.TokenHash, Data: data, SHA256: stamp, CreatedAt: now, ExpiresAt: now.Add(exportdomain.DirectoryPreviewTTL)}
	completion.Persist, err = service.repository.CompleteDirectoryPreflight(ctx, task.ID, record)
	return completion, err
}
