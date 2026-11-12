package ports

import (
	"context"

	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

type Completion struct {
	// 在写事务前复核外部效果，返回的释放动作由 Store 保持到数据库收尾完成。
	BeforePersist func(context.Context) (func(), error)
	Persist       persistence.WriteAction
	// 与失败状态一起提交；工作器关闭并重新排队时不执行。
	PersistFailure persistence.WriteAction
	// 仅在完成事务成功后清理工作区；清理失败不回退已经提交的成功状态。
	AfterCommit func(context.Context) error
}

// Admission 只转交由适配器生成的原子动作，业务服务不接触 SQL 事务。
type Admission func(taskID string) persistence.WriteAction

type BatchAdmission func(taskIDs []string) persistence.WriteAction

type Repository interface {
	Events(context.Context, string, int64, int) (jobsdomain.TaskEventPage, error)
	GetImportBatch(context.Context, string) (jobsdomain.TaskBatch, error)
	CreateImportBatch(context.Context, []jobsdomain.PreparedImport, string, string, BatchAdmission) (jobsdomain.TaskBatch, error)
	List(context.Context, jobsdomain.ListQuery) (jobsdomain.TaskPage, error)
	Summary(context.Context, jobsdomain.Filter) (jobsdomain.Summary, error)
	Inspect(context.Context, string) (jobsdomain.Task, error)
	Get(ctx context.Context, id string) (jobsdomain.Task, error)
	CreateTask(ctx context.Context, kind, source, title, key, requestHash string, explicit bool) (jobsdomain.Task, error)
	CreateResourceTask(context.Context, jobsdomain.ResourceTask, string, string, Admission) (jobsdomain.Task, error)
	Claim(ctx context.Context) (jobsdomain.Task, error)
	Recover(ctx context.Context) error
	UpdateProgress(ctx context.Context, id, stage string, total, completed int) error
	Publishing(ctx context.Context, id, manifest string) error
	ClaimImportSource(context.Context, string) error
	Complete(ctx context.Context, id string, completion Completion) error
	Finish(ctx context.Context, id, status, code string, persist persistence.WriteAction) (jobsdomain.Task, error)
	Cancel(ctx context.Context, id string) error
	Retry(ctx context.Context, id string) error
	RetryGuarded(context.Context, string, persistence.WriteAction) error
	Requeue(context.Context, string) error
}
