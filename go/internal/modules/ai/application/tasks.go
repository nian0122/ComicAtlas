package application

import (
	aiports "comicatlas.local/backend/internal/modules/ai/application/ports"
	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"context"
	"encoding/json"
	"errors"
	"time"
)

type Tasks struct {
	repository aiports.TaskRepository
	sampler    *Sampler
	backend    aiports.Backend
	jobs       *jobsapp.Store
}

func NewTasks(repository aiports.TaskRepository, sampler *Sampler, backend aiports.Backend, jobs *jobsapp.Store) *Tasks {
	return &Tasks{repository: repository, sampler: sampler, backend: backend, jobs: jobs}
}

func (service *Tasks) Submit(ctx context.Context, comic string, request aidomain.AnalysisRequest, key string) (jobsdomain.Task, error) {
	if service.backend == nil {
		return jobsdomain.Task{}, aidomain.ErrUnavailable
	}
	request, err := aidomain.NormalizeAnalysisRequest(comic, request)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	data, err := json.Marshal(request)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "ai_analysis", ComicID: comic, TargetType: "comic", TargetID: comic, Title: "AI 分析 · " + comic, Payload: string(data)}, key, service.repository.Admission(comic, request))
}

func (service *Tasks) Retry(ctx context.Context, task string) error {
	if service.backend == nil {
		return aidomain.ErrUnavailable
	}
	return service.jobs.RetryGuarded(ctx, task, service.repository.Retry(task))
}

func (service *Tasks) Result(ctx context.Context, task string) (*aidomain.AnalysisResult, error) {
	run, err := service.repository.Run(ctx, task)
	return run.Result, err
}

func (service *Tasks) Run(parent context.Context, task jobsdomain.Task) (completion jobsports.Completion, err error) {
	defer func() {
		if err != nil && parent.Err() == nil {
			err = jobsdomain.NewFailure(analysisFailure(err), err)
		}
	}()
	if task.Type != "ai_analysis" || task.Status != "running" || task.Attempt < 1 || task.TargetType != "comic" || task.TargetID != task.ComicID || task.RequestJSON == nil {
		return completion, aidomain.ErrValidation
	}
	if service.backend == nil {
		return completion, aidomain.ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(parent, 30*time.Minute)
	defer cancel()
	var request aidomain.AnalysisRequest
	if json.Unmarshal([]byte(*task.RequestJSON), &request) != nil {
		return completion, aidomain.ErrValidation
	}
	run, err := service.repository.Run(ctx, task.ID)
	if err != nil {
		return completion, err
	}
	if run.Result != nil || run.Snapshot.ComicID != task.ComicID || run.SampleCount != request.SampleCount {
		return completion, aidomain.ErrSource
	}
	if err = aidomain.ValidateAnalysisVersion(request, run.Snapshot); err != nil {
		return completion, err
	}
	if err = service.repository.Check(ctx, task.ID); err != nil {
		return completion, err
	}
	if err = service.jobs.UpdateProgress(ctx, task.ID, "preparing", 2, 0); err != nil {
		return completion, err
	}
	prepared, samples, err := service.sampler.prepareSnapshot(ctx, aidomain.AnalysisRequestID(task.ID, task.Attempt), run.Snapshot)
	if err != nil {
		return completion, err
	}
	defer func() {
		if cleanup := samples.Close(); cleanup != nil {
			err = errors.Join(err, cleanup)
			completion = jobsports.Completion{}
		}
	}()
	if err = service.repository.Check(ctx, task.ID); err != nil {
		return completion, err
	}
	if err = service.jobs.UpdateProgress(ctx, task.ID, "analyzing", 2, 1); err != nil {
		return completion, err
	}
	candidate, err := service.backend.Analyze(ctx, prepared, samples)
	if err != nil {
		return completion, err
	}
	if err = ctx.Err(); err != nil {
		return completion, err
	}
	if err = aidomain.ValidateCandidate(prepared, candidate); err != nil {
		return completion, err
	}
	return jobsports.Completion{Persist: service.repository.Completion(task.ID, task.Attempt, prepared, candidate)}, nil
}

func analysisFailure(err error) string {
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return jobsdomain.FailureAITimeout
	case errors.Is(err, aidomain.ErrUnavailable):
		return jobsdomain.FailureAIUnavailable
	case errors.Is(err, aidomain.ErrBusy):
		return jobsdomain.FailureAIBusy
	case errors.Is(err, aidomain.ErrProtocol):
		return jobsdomain.FailureAIProtocol
	case errors.Is(err, aidomain.ErrSource), errors.Is(err, aidomain.ErrNotFound):
		return jobsdomain.FailureAISource
	default:
		return jobsdomain.FailureAI
	}
}
