package application

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	recoveryports "comicatlas.local/backend/internal/modules/recovery/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"errors"
	"time"
)

type BookService struct {
	repository recoveryports.RestoreRepository
	sources    recoveryports.Observations
}

func NewBookService(repository recoveryports.RestoreRepository, sources recoveryports.Observations) *BookService {
	return &BookService{repository: repository, sources: sources}
}

// Process 处理一本封存候选，区分完整恢复、缺元数据占位、跳过及逐本错误。
func (service *BookService) Process(ctx context.Context, task jobsdomain.Task, comic string) (result recoverydomain.Outcome, err error) {
	if err = ctx.Err(); err != nil {
		return result, err
	}
	if task.Type != "disk_recovery" || task.Status != "running" || task.Attempt < 1 || !recoverydomain.Key(comic) {
		return result, recoverydomain.ErrValidation
	}
	run, err := service.repository.Discovery(ctx, task.ID)
	if err != nil {
		return result, err
	}
	if !run.Sealed || run.TaskStatus != "running" || run.Attempt != task.Attempt {
		return result, recoverydomain.ErrConflict
	}
	source := service.sources.Observe()
	candidate, prepareErr := NewCandidatePreparer(service.repository, source).Prepare(ctx, comic, nil)
	value := recoverydomain.Outcome{TaskID: task.ID, ComicID: comic, TaskAttempt: task.Attempt, UpdatedAt: time.Now().UTC().Format(jobsdomain.TimestampFormat)}
	failure := func(cause error) (recoverydomain.Outcome, error) {
		if ctx.Err() != nil {
			return recoverydomain.Outcome{}, errors.Join(cause, ctx.Err())
		}
		status, code, handled := recoverydomain.WriteFailure(cause)
		if !handled {
			return recoverydomain.Outcome{}, cause
		}
		value.Status, value.ErrorCode = status, code
		return service.repository.CommitOutcome(ctx, value, nil)
	}
	if prepareErr != nil {
		return failure(prepareErr)
	}
	switch candidate.Kind {
	case recoverydomain.CandidateExisting:
		value.Status = recoverydomain.ResultSkipped
		return service.repository.CommitOutcome(ctx, value, nil)
	case recoverydomain.CandidateNeedsMetadata:
		write := service.repository.PlaceholderWriter(comic, task.ID, value.UpdatedAt, candidate.Presence)
		if err = source.Recheck(ctx); err != nil {
			return failure(err)
		}
		value.Status = recoverydomain.ResultPlaceholder
		return service.repository.CommitOutcome(ctx, value, write)
	case recoverydomain.CandidateEmpty:
		if err = source.Recheck(ctx); err != nil {
			return failure(err)
		}
		value.Status = recoverydomain.ResultEmpty
		return service.repository.CommitOutcome(ctx, value, nil)
	case recoverydomain.CandidateBytesVerified:
		document := candidate.Document
		if document == nil {
			return result, recoverydomain.ErrMetadata
		}
		write := service.repository.RestoreWriter(comic, document.Sidecar, task.ID, value.UpdatedAt)
		if err = source.Recheck(ctx); err != nil {
			return failure(err)
		}
		value.Status, value.SourceSHA256 = recoverydomain.ResultRestored, &document.SHA256
		return service.repository.CommitOutcome(ctx, value, write)
	default:
		return result, recoverydomain.ErrValidation
	}
}
