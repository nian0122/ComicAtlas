package application

import (
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	storageports "comicatlas.local/backend/internal/modules/storage/application/ports"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"context"
	"errors"
	"sync"
	"time"
)

type Sidecars struct {
	repository storageports.SidecarRepository
	files      storageports.SidecarFiles
	library    storageports.SidecarLibrary
	jobs       *jobsapp.Store
	fence      storageports.SidecarFence
	mutex      sync.Mutex
	cursor     string
}

func NewSidecars(repo storageports.SidecarRepository, files storageports.SidecarFiles, library storageports.SidecarLibrary, jobs *jobsapp.Store, fence storageports.SidecarFence) *Sidecars {
	return &Sidecars{repository: repo, files: files, library: library, jobs: jobs, fence: fence}
}

func (service *Sidecars) submit(ctx context.Context, state storagedomain.SidecarState, key string, force bool) (jobsdomain.Task, error) {
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "metadata_sync", TargetType: "comic", TargetID: state.ComicID, ComicID: state.ComicID, Title: state.Title, Payload: "{}"}, key, service.repository.SidecarAdmission(state.ComicID, force))
}

func (service *Sidecars) Request(ctx context.Context, id, key string) (jobsdomain.Task, error) {
	if id == "" || len(id) > 128 {
		return jobsdomain.Task{}, storagedomain.ErrValidation
	}
	state, err := service.repository.SidecarState(ctx, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.submit(ctx, state, key, true)
}

func (service *Sidecars) Status(ctx context.Context, id string) (storagedomain.SidecarStatus, error) {
	if id == "" || len(id) > 128 {
		return storagedomain.SidecarStatus{}, storagedomain.ErrValidation
	}
	state, err := service.repository.SidecarState(ctx, id)
	if err != nil {
		return storagedomain.SidecarStatus{}, err
	}
	return storagedomain.SidecarView(state), nil
}

func (service *Sidecars) Retry(ctx context.Context, id string) error {
	return service.jobs.RetryGuarded(ctx, id, service.repository.SidecarRetry(id))
}

func (service *Sidecars) Schedule(ctx context.Context, now time.Time) (int, error) {
	service.mutex.Lock()
	defer service.mutex.Unlock()
	states, err := service.repository.SidecarCandidates(ctx, service.cursor)
	if err != nil {
		return 0, err
	}
	if len(states) == 0 && service.cursor != "" {
		service.cursor = ""
		states, err = service.repository.SidecarCandidates(ctx, "")
		if err != nil {
			return 0, err
		}
	}
	created := 0
	for _, state := range states {
		service.cursor = state.ComicID
		switch storagedomain.SidecarAction(state, now) {
		case "create":
			_, err = service.submit(ctx, state, "", false)
		case "retry":
			if state.TaskID == nil {
				continue
			}
			err = service.Retry(ctx, *state.TaskID)
		default:
			continue
		}
		if err != nil {
			if errors.Is(err, storagedomain.ErrConflict) || errors.Is(err, jobsdomain.ErrConflict) || errors.Is(err, jobsdomain.ErrFull) || errors.Is(err, storagedomain.ErrNotFound) {
				continue
			}
			return created, err
		}
		created++
	}
	return created, nil
}

func (service *Sidecars) Maintain(ctx context.Context, wake func(), onError func(error)) {
	ticker := time.NewTicker(storagedomain.SidecarPoll)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			count, err := service.Schedule(ctx, now)
			if count > 0 {
				wake()
			}
			if err != nil && ctx.Err() == nil && onError != nil {
				onError(err)
			}
		}
	}
}

func (service *Sidecars) Run(parent context.Context, task jobsdomain.Task) (completion jobsports.Completion, err error) {
	defer func() {
		if err != nil && parent.Err() == nil {
			err = jobsdomain.NewFailure(jobsdomain.FailureMetadataSync, err)
		}
	}()
	ctx, cancel := context.WithTimeout(parent, 2*time.Minute)
	defer cancel()
	release, err := service.fence.AcquireFileMutation(ctx)
	if err != nil {
		return completion, err
	}
	defer release()
	state, err := service.repository.SidecarState(ctx, task.ComicID)
	if err != nil {
		return completion, err
	}
	if state.TaskID == nil || *state.TaskID != task.ID || state.Blocked {
		return completion, storagedomain.ErrConflict
	}
	if err = service.jobs.UpdateProgress(ctx, task.ID, "snapshotting", 1, 0); err != nil {
		return completion, err
	}
	snapshot, err := service.library.SnapshotForSidecar(ctx, task.ComicID)
	if err != nil {
		return completion, err
	}
	if snapshot.ComicID != task.ComicID {
		return completion, storagedomain.ErrConflict
	}
	observed := time.Now().UTC().Format(jobsdomain.TimestampFormat)
	data, hash, err := storagedomain.AutomaticSidecarData(task.ID, snapshot, observed)
	if err != nil {
		return completion, err
	}
	if err = service.repository.PrepareSidecar(ctx, task.ID, snapshot.Revision, hash, observed); err != nil {
		return completion, err
	}
	if err = service.jobs.Publishing(ctx, task.ID, `{"version":1}`); err != nil {
		return completion, err
	}
	if err = service.files.PublishMetadata(ctx, task.ComicID, task.ID, data, hash); err != nil {
		return completion, err
	}
	return jobsports.Completion{Persist: service.repository.SidecarCompletion(task.ID, hash)}, nil
}

func (service *Sidecars) Items(ctx context.Context, id string, page, size int) (storagedomain.SidecarPage, error) {
	if page < 1 || page > 1000000 || size < 1 || size > 100 {
		return storagedomain.SidecarPage{}, storagedomain.ErrValidation
	}
	result, err := service.repository.SidecarResult(ctx, id)
	if err != nil {
		return storagedomain.SidecarPage{}, err
	}
	items := []storagedomain.SidecarResult{}
	if page == 1 {
		items = append(items, result)
	}
	return storagedomain.SidecarPage{Items: items, Total: 1, Page: page, PageSize: size}, nil
}
