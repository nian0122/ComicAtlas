package application

import (
	"context"
	"crypto/sha256"
	"fmt"
	"log/slog"

	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

func (store *Store) Get(ctx context.Context, id string) (jobsdomain.Task, error) {
	return store.repository.Get(ctx, id)
}

func (store *Store) Create(ctx context.Context, source, title, key string) (jobsdomain.Task, error) {
	return store.create(ctx, "directory_import", source, title, key)
}

func (store *Store) CreateArchive(ctx context.Context, source, title, key string) (jobsdomain.Task, error) {
	return store.create(ctx, "archive_import", source, title, key)
}

func (store *Store) CreateResource(ctx context.Context, request jobsdomain.ResourceTask, key string, admission jobsports.Admission) (jobsdomain.Task, error) {
	if err := jobsdomain.ValidateResourceTask(request, key); err != nil || admission == nil {
		return jobsdomain.Task{}, jobsdomain.ErrValidation
	}
	identity := request.Type + "\x00" + request.TargetType + "\x00" + request.TargetID + "\x00" + request.Payload
	hash := fmt.Sprintf("%x", sha256.Sum256([]byte(identity)))
	return store.repository.CreateResourceTask(ctx, request, key, hash, admission)
}

func (store *Store) create(ctx context.Context, kind, source, title, key string) (jobsdomain.Task, error) {
	value, err := jobsdomain.PrepareImport(jobsdomain.ImportInput{Type: kind, SourcePath: source, Title: title}, key)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return store.repository.CreateTask(ctx, kind, value.Resource.SourcePath, value.Resource.Title, key, value.RequestHash, value.Explicit)
}

func (store *Store) claim(ctx context.Context) (jobsdomain.Task, error) {
	return store.repository.Claim(ctx)
}

func (store *Store) Recover(ctx context.Context) error { return store.repository.Recover(ctx) }

func (store *Store) UpdateProgress(ctx context.Context, id, stage string, total, completed int) error {
	return store.repository.UpdateProgress(ctx, id, stage, total, completed)
}

func (store *Store) Publishing(ctx context.Context, id, manifest string) error {
	return store.repository.Publishing(ctx, id, manifest)
}
func (store *Store) ClaimImportSource(ctx context.Context, id string) error {
	return store.repository.ClaimImportSource(ctx, id)
}

func (store *Store) Complete(ctx context.Context, id string, completion jobsports.Completion) error {
	if completion.BeforePersist != nil {
		release, err := completion.BeforePersist(ctx)
		if release != nil {
			defer release()
		}
		if err != nil {
			return jobsdomain.CompletionError("VERIFY", err)
		}
		if release == nil {
			return jobsdomain.CompletionError("VERIFY", jobsdomain.ErrConflict)
		}
	}
	if err := store.repository.Complete(ctx, id, completion); err != nil {
		return err
	}
	if completion.AfterCommit != nil {
		if err := completion.AfterCommit(ctx); err != nil {
			slog.Warn("任务已成功，提交后处理未完成", "taskId", id, "errorCode", "TASK_AFTER_COMMIT_FAILED")
		}
	}
	return nil
}

func (store *Store) finish(ctx context.Context, id, status, code string, persist persistence.WriteAction) (jobsdomain.Task, error) {
	return store.repository.Finish(ctx, id, status, code, persist)
}

func (store *Store) Cancel(ctx context.Context, id string) error {
	return store.repository.Cancel(ctx, id)
}

func (store *Store) Retry(ctx context.Context, id string) error {
	return store.repository.Retry(ctx, id)
}

func (store *Store) RetryGuarded(ctx context.Context, id string, guard persistence.WriteAction) error {
	if guard == nil {
		return jobsdomain.ErrValidation
	}
	return store.repository.RetryGuarded(ctx, id, guard)
}

type Store struct{ repository jobsports.Repository }

func NewStore(repository jobsports.Repository) *Store { return &Store{repository: repository} }
