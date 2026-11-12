package application

import (
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	recoveryports "comicatlas.local/backend/internal/modules/recovery/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"encoding/json"
	"errors"
	"time"
)

type Repair struct {
	repository recoveryports.RepairRepository
	sources    recoveryports.Observations
	jobs       *jobsapp.Store
}

func NewRepair(repository recoveryports.RepairRepository, sources recoveryports.Observations, jobs *jobsapp.Store) *Repair {
	return &Repair{repository: repository, sources: sources, jobs: jobs}
}
func (service *Repair) Submit(ctx context.Context, comic string, request recoverydomain.RepairRequest, key string) (jobsdomain.Task, error) {
	if err := recoverydomain.ValidateRepairRequest(comic, request); err != nil {
		return jobsdomain.Task{}, err
	}
	payload, err := json.Marshal(request)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "recovery_repair", TargetType: "comic", TargetID: comic, ComicID: comic, Title: "补全 · " + comic, Payload: string(payload)}, key, service.repository.RepairAdmission(comic, request))
}
func (service *Repair) Run(parent context.Context, task jobsdomain.Task) (completion jobsports.Completion, err error) {
	defer func() {
		if err != nil && parent.Err() == nil {
			code := jobsdomain.FailureRecovery
			if errors.Is(err, context.DeadlineExceeded) {
				code = jobsdomain.FailureRecoveryTimeout
			}
			err = jobsdomain.NewFailure(code, err)
		}
	}()
	if task.Type != "recovery_repair" || task.ComicID == "" || task.TargetID != task.ComicID || task.Status != "running" || task.Attempt < 1 {
		return completion, recoverydomain.ErrValidation
	}
	ctx, cancel := context.WithTimeout(parent, 2*time.Hour)
	defer cancel()
	run, err := service.repository.RepairRun(ctx, task.ID)
	if err != nil {
		return completion, err
	}
	if run.ComicID != task.ComicID || run.Completed {
		return completion, recoverydomain.ErrConflict
	}
	if err = service.jobs.UpdateProgress(ctx, task.ID, "verifying", 1, 0); err != nil {
		return completion, err
	}
	source := service.sources.Observe()
	document, err := source.ReadSidecar(ctx, task.ComicID)
	if err != nil {
		return completion, err
	}
	if _, err = NewMediaVerifier(source).Verify(ctx, task.ComicID, document.Sidecar, nil); err != nil {
		return completion, err
	}
	write := service.repository.RepairWriter(run, document.Sidecar, task.Attempt, document.SHA256, time.Now().UTC().Format(jobsdomain.TimestampFormat))
	if err = source.Recheck(ctx); err != nil {
		return completion, err
	}
	return jobsports.Completion{Persist: write}, nil
}
func (service *Repair) Result(ctx context.Context, task string) (recoverydomain.RepairRun, error) {
	return service.repository.RepairRun(ctx, task)
}

func (service *Repair) Retry(ctx context.Context, task string) error {
	return service.jobs.RetryGuarded(ctx, task, service.repository.RepairRetry(task))
}
