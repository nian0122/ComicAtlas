package application

import (
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	storageports "comicatlas.local/backend/internal/modules/storage/application/ports"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"context"
	"errors"
	"time"
)

type MetadataService struct {
	repository storageports.MetadataRepository
	files      storageports.MetadataFiles
	library    storageports.MetadataLibrary
	jobs       *jobsapp.Store
}

func NewMetadata(repo storageports.MetadataRepository, files storageports.MetadataFiles, library storageports.MetadataLibrary, jobs *jobsapp.Store) *MetadataService {
	return &MetadataService{repo, files, library, jobs}
}
func (service *MetadataService) Submit(ctx context.Context, id, key string) (jobsdomain.Task, error) {
	return service.SubmitGuarded(ctx, id, key, nil)
}
func (service *MetadataService) SubmitGuarded(ctx context.Context, id, key string, guard jobsports.Admission) (jobsdomain.Task, error) {
	if id == "" || len(id) > 128 {
		return jobsdomain.Task{}, storagedomain.ErrValidation
	}
	snapshot, err := service.library.Snapshot(ctx, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "metadata_refresh", TargetType: "comic", TargetID: id, ComicID: id, Title: snapshot.Detail.Title, Payload: "{}"}, key, jobsports.CombineAdmissions(guard, service.repository.MetadataAdmission(snapshot, service.library.SnapshotGuard(snapshot))))
}
func (service *MetadataService) Run(parent context.Context, task jobsdomain.Task) (completion jobsports.Completion, err error) {
	defer func() {
		if err != nil && parent.Err() == nil {
			code := jobsdomain.FailureMetadataRefresh
			if errors.Is(err, context.DeadlineExceeded) {
				code = jobsdomain.FailureMetadataRefreshTimeout
			}
			if errors.Is(err, librarydomain.ErrConflict) || errors.Is(err, storagedomain.ErrConflict) {
				code = jobsdomain.FailureMetadataRefreshConflict
			}
			if errors.Is(err, storagedomain.ErrScanLimit) {
				code = jobsdomain.FailureMetadataRefreshLimit
			}
			err = jobsdomain.NewFailure(code, err)
		}
	}()
	ctx, cancel := context.WithTimeout(parent, 2*time.Hour)
	defer cancel()
	run, err := service.repository.MetadataRun(ctx, task.ID)
	if err != nil {
		return completion, err
	}
	if run.Baseline.Detail.ID != task.ComicID || task.TargetID != task.ComicID {
		return completion, storagedomain.ErrValidation
	}
	if task.Manifest == nil {
		if err = service.jobs.UpdateProgress(ctx, task.ID, "scanning", -1, 0); err != nil {
			return completion, err
		}
		// 文件系统按项回调，这里限频写入任务进度，避免大库逐项写库，
		// 同时让只有少量媒体的漫画也能看到已处理数量变化。
		last := time.Time{}
		report := func(count int) error {
			if time.Since(last) < 500*time.Millisecond {
				return nil
			}
			last = time.Now()
			return service.jobs.UpdateProgress(ctx, task.ID, "scanning", -1, count)
		}
		items, scanErr := service.files.ObserveMetadata(ctx, run.Baseline, report)
		if scanErr != nil {
			return completion, scanErr
		}
		next, applyErr := storagedomain.ApplyObservations(run.Baseline, items)
		if applyErr != nil {
			return completion, applyErr
		}
		run.ObservedAt = time.Now().UTC().Format(jobsdomain.TimestampFormat)
		if err = service.repository.PrepareMetadata(ctx, task.ID, next, items, run.ObservedAt); err != nil {
			return completion, err
		}
		if err = service.jobs.UpdateProgress(ctx, task.ID, "scanning", len(items), len(items)); err != nil {
			return completion, err
		}
		if err = service.jobs.Publishing(ctx, task.ID, `{"version":1}`); err != nil {
			return completion, err
		}
		run.Next, run.Observations = &next, items
	}
	if run.Next == nil {
		return completion, storagedomain.ErrValidation
	}
	data, hash, err := storagedomain.MetadataSidecarData(task.ID, run)
	if err != nil {
		return completion, err
	}
	if !run.Applied {
		if err = service.repository.ActivateMetadata(ctx, task.ID, service.library.StorageWriter(run.Baseline, *run.Next)); err != nil {
			return completion, err
		}
	}
	if err = service.files.PublishMetadata(ctx, task.ComicID, task.ID, data, hash); err != nil {
		return completion, err
	}
	return jobsports.Completion{Persist: service.repository.MetadataCompletion(task.ID, hash)}, nil
}
func (service *MetadataService) Items(ctx context.Context, id string, page, size int) (storagedomain.MetadataPage, error) {
	if page < 1 || page > 1000000 || size < 1 || size > 100 {
		return storagedomain.MetadataPage{}, storagedomain.ErrValidation
	}
	return service.repository.MetadataItems(ctx, id, page, size)
}
