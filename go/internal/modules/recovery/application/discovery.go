package application

import (
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	recoveryports "comicatlas.local/backend/internal/modules/recovery/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"errors"
)

type DiscoveryService struct {
	repository recoveryports.DiscoveryRepository
	source     recoveryports.DiscoverySource
	jobs       *jobsapp.Store
}

func NewDiscoveryService(repository recoveryports.DiscoveryRepository, source recoveryports.DiscoverySource, jobs *jobsapp.Store) *DiscoveryService {
	return &DiscoveryService{repository: repository, source: source, jobs: jobs}
}

func (service *DiscoveryService) Create(ctx context.Context, key string) (jobsdomain.Task, error) {
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "disk_recovery", TargetType: "storage", TargetID: "media", Title: "扫盘恢复", Payload: "{}"}, key, service.repository.DiscoveryAdmission())
}

// Discover 只封存候选，不返回任务完成动作；逐本恢复和最终收尾尚须继续。
func (service *DiscoveryService) Discover(ctx context.Context, task string) (result recoverydomain.DiscoveryRun, err error) {
	defer func() {
		err = errors.Join(err, ctx.Err())
		if err != nil {
			result = recoverydomain.DiscoveryRun{}
		}
	}()
	if err = ctx.Err(); err != nil {
		return result, err
	}
	result, err = service.repository.BeginDiscovery(ctx, task)
	if err != nil || result.Sealed {
		return result, err
	}
	batch := make([]string, 0, recoverydomain.DiscoveryBatchSize)
	flush := func() error {
		if len(batch) == 0 {
			return nil
		}
		if err := service.repository.AppendDiscovery(ctx, task, batch); err != nil {
			return err
		}
		batch = batch[:0]
		return nil
	}
	found, err := service.source.ScanHQ(ctx, func(id string) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		batch = append(batch, id)
		if len(batch) == recoverydomain.DiscoveryBatchSize {
			return flush()
		}
		return nil
	})
	if err != nil {
		return result, err
	}
	if err = flush(); err != nil {
		return result, err
	}
	return service.repository.SealDiscovery(ctx, task, found)
}
