package application

import (
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	storageports "comicatlas.local/backend/internal/modules/storage/application/ports"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"context"
	"errors"
	"time"
)

const (
	// metricsRefreshBatch 限制单个写事务重算的漫画数量，保持事务短小。
	metricsRefreshBatch = 64
	// scanDeadline 是单次扫盘的安全期限，覆盖百万条目与慢速网络卷；挂死的文件调用由取消回收。
	scanDeadline = 2 * time.Hour
	// progressInterval 限制进度写库频率。慢速卷上每次提交都要同步落盘，按条目写会拖慢扫描。
	progressInterval = 5 * time.Second
)

type Service struct {
	repository storageports.Repository
	scanner    storageports.Scanner
	capacity   storageports.CapacityReader
	jobs       *jobsapp.Store
}

func New(repository storageports.Repository, scanner storageports.Scanner, jobs *jobsapp.Store, capacity storageports.CapacityReader) *Service {
	return &Service{repository: repository, scanner: scanner, jobs: jobs, capacity: capacity}
}
func (service *Service) Statistics(ctx context.Context, kind, id string) (storagedomain.Statistics, error) {
	if err := storagedomain.ValidateScope(kind, id); err != nil {
		return storagedomain.Statistics{}, err
	}
	inventory, err := service.repository.Inventory(ctx, kind, id)
	if err != nil {
		return storagedomain.Statistics{}, err
	}
	result := storagedomain.Statistics{Inventory: inventory}
	if kind != "global" {
		return result, nil
	}
	observation, refresh, err := service.repository.GlobalDetails(ctx)
	if err != nil {
		return storagedomain.Statistics{}, err
	}
	result.Observation, result.Refresh = observation, refresh
	if service.capacity != nil {
		capacity, capacityErr := service.capacity.Read(ctx)
		if capacityErr != nil {
			return storagedomain.Statistics{}, capacityErr
		}
		result.Device = &capacity
	}
	return result, nil
}

// RefreshAllMetrics 重算全部脏漫画，供定时任务调用。
func (service *Service) RefreshAllMetrics(ctx context.Context) (int, error) {
	total := 0
	for {
		count, err := service.repository.RefreshMetrics(ctx, metricsRefreshBatch)
		if err != nil {
			return total, err
		}
		total += count
		if count < metricsRefreshBatch {
			return total, nil
		}
	}
}

func (service *Service) Refresh(ctx context.Context, key string) (jobsdomain.Task, error) {
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "storage_refresh", TargetType: "storage", TargetID: "media", Title: "存储占用刷新", Payload: "{}"}, key, service.repository.Admission())
}

// ScheduleRefresh 只负责投递；同一媒体卷已有活跃扫描时本轮跳过。
func (service *Service) ScheduleRefresh(ctx context.Context) (int, error) {
	_, err := service.Refresh(ctx, "")
	if errors.Is(err, jobsdomain.ErrTargetActive) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	return 1, nil
}

func (service *Service) Run(ctx context.Context, task jobsdomain.Task) (jobsports.Completion, error) {
	started := time.Now().UTC().Format(jobsdomain.TimestampFormat)
	scanCtx, cancel := context.WithTimeout(ctx, scanDeadline)
	defer cancel()
	if err := service.jobs.UpdateProgress(scanCtx, task.ID, "scanning", -1, 0); err != nil {
		return jobsports.Completion{}, err
	}
	clock := progressClock{interval: progressInterval}
	usage, err := service.scanner.Scan(scanCtx, func(count int) error {
		if !clock.due(time.Now()) {
			return nil
		}
		return service.jobs.UpdateProgress(scanCtx, task.ID, "scanning", -1, count)
	})
	if err != nil {
		if ctx.Err() != nil {
			return jobsports.Completion{}, ctx.Err()
		}
		code := jobsdomain.FailureStorageRead
		if errors.Is(err, storagedomain.ErrScanLimit) {
			code = jobsdomain.FailureStorageLimit
		}
		if errors.Is(err, context.DeadlineExceeded) {
			code = jobsdomain.FailureStorageTimeout
		}
		return jobsports.Completion{}, jobsdomain.NewFailure(code, err)
	}
	if err = service.jobs.UpdateProgress(ctx, task.ID, "scanning", int(usage.Entries), int(usage.Entries)); err != nil {
		return jobsports.Completion{}, err
	}
	return jobsports.Completion{Persist: service.repository.Completion(storagedomain.Observation{TaskID: task.ID, StartedAt: started, FinishedAt: time.Now().UTC().Format(jobsdomain.TimestampFormat), Usage: usage})}, nil
}
