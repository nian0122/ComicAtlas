package application

import (
	lifecycleports "comicatlas.local/backend/internal/modules/lifecycle/application/ports"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
)

// 小批次写入实际完成的观察；失败后不重复提交可能已提交过的批次。
type resultRecorder struct {
	ctx        context.Context
	repository lifecycleports.Repository
	task       string
	attempt    int
	offset     int
	batch      []lifecycledomain.FileResult
	err        error
}

func (recorder *resultRecorder) record(item lifecycledomain.FileResult) error {
	if recorder.err != nil {
		return recorder.err
	}
	item.Position += recorder.offset
	recorder.batch = append(recorder.batch, item)
	if len(recorder.batch) >= lifecycledomain.MaxFileResultBatch {
		return recorder.flush()
	}
	return nil
}

func (recorder *resultRecorder) flush() error {
	if recorder.err != nil {
		return recorder.err
	}
	if len(recorder.batch) == 0 {
		return nil
	}
	recorder.err = recorder.repository.RecordFileResults(recorder.ctx, recorder.task, recorder.attempt, recorder.batch)
	if recorder.err == nil {
		recorder.batch = recorder.batch[:0]
	}
	return recorder.err
}

func (service *Service) FileResults(ctx context.Context, task string, page, size int) (lifecycledomain.FileResultPage, error) {
	if !lifecycledomain.ValidJournalID(task) || page < 1 || page > 1000000 || size < 1 || size > 100 {
		return lifecycledomain.FileResultPage{}, lifecycledomain.ErrValidation
	}
	return service.repository.FileResults(ctx, task, page, size)
}
