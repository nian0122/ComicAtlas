package application

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"context"
	"encoding/json"
	"errors"
	"time"
)

type Service struct {
	repository exportports.Repository
	files      exportports.Files
	jobs       *jobsapp.Store
}

func New(repo exportports.Repository, files exportports.Files, jobs *jobsapp.Store) *Service {
	return &Service{repo, files, jobs}
}
func (service *Service) CleanupCancelled(ctx context.Context) (int, error) {
	return service.files.CleanupCancelled(ctx, service.repository.Cancelled)
}
func (service *Service) Submit(ctx context.Context, id string, request exportdomain.Request, key string) (jobsdomain.Task, error) {
	request, err := exportdomain.Normalize(request)
	if err != nil || id == "" || len(id) > 128 {
		return jobsdomain.Task{}, exportdomain.ErrValidation
	}
	title, err := service.repository.Title(ctx, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	payload, err := json.Marshal(request)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "archive_export", ComicID: id, TargetType: "comic", TargetID: id, Title: title, Payload: string(payload)}, key, service.repository.Admission(id, request))
}
func (service *Service) Artifacts(ctx context.Context, id string) (exportdomain.ArtifactView, error) {
	view, err := service.repository.Artifacts(ctx, id)
	if err == nil && view.Published {
		directory := "exports/" + id
		view.Directory = &directory
		for index := range view.Items {
			view.Items[index].Path = directory + "/" + view.Items[index].FileName
		}
	}
	return view, err
}
func (service *Service) Run(ctx context.Context, task jobsdomain.Task) (completion jobsports.Completion, err error) {
	defer func() {
		if err != nil && ctx.Err() == nil {
			code := jobsdomain.FailureExport
			switch {
			case errors.Is(err, exportdomain.ErrManifest):
				code = jobsdomain.FailureManifest
			case errors.Is(err, exportdomain.ErrSource):
				code = jobsdomain.FailureExportSource
			case errors.Is(err, exportdomain.ErrConflict):
				code = jobsdomain.FailureExportConflict
			case errors.Is(err, context.DeadlineExceeded):
				code = jobsdomain.FailureExportTimeout
			}
			err = jobsdomain.NewFailure(code, err)
		}
	}()
	run, cancel := context.WithTimeout(ctx, 2*time.Hour)
	defer cancel()
	snapshot, hash, err := service.repository.Load(run, task.ID)
	if err != nil {
		return completion, err
	}
	if task.Type != "archive_export" || snapshot.Comic.ID != task.ComicID || task.TargetID != task.ComicID {
		return completion, exportdomain.ErrManifest
	}
	manifest := exportdomain.Manifest{Version: 2, TaskID: task.ID, SnapshotSHA: hash}
	if task.Manifest != nil {
		if json.Unmarshal([]byte(*task.Manifest), &manifest) != nil || exportdomain.ValidateManifest(manifest, task.ID, hash, snapshot) != nil {
			return completion, exportdomain.ErrManifest
		}
	} else {
		retained := false
		defer func() {
			if !retained {
				cleanup, done := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
				defer done()
				err = errors.Join(err, service.files.Cleanup(cleanup, task.ID))
			}
		}()
		if err = service.files.Cleanup(run, task.ID); err != nil {
			return completion, err
		}
		total := len(snapshot.Sources) + 2
		if err = service.jobs.UpdateProgress(run, task.ID, "writing", total, 0); err != nil {
			return completion, err
		}
		manifest.Artifacts, err = service.files.Build(run, task.ID, snapshot, func(completed int) error {
			if completed%64 == 0 || completed == total {
				return service.jobs.UpdateProgress(run, task.ID, "writing", total, completed)
			}
			return run.Err()
		})
		if err != nil {
			return completion, err
		}
		if err = exportdomain.ValidateManifest(manifest, task.ID, hash, snapshot); err != nil {
			return completion, err
		}
		data, marshalErr := json.Marshal(manifest)
		if marshalErr != nil {
			return completion, marshalErr
		}
		if err = service.jobs.Publishing(run, task.ID, string(data)); err != nil {
			return completion, err
		}
		retained = true
	}
	if err = service.files.Publish(run, task.ID, manifest.Artifacts); err != nil {
		return completion, err
	}
	return jobsports.Completion{Persist: service.repository.Completion(manifest)}, nil
}
