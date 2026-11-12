package main

import (
	"context"
	"fmt"

	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
)

// 只分派启动时明确组装的任务；AI 与批次父任务由各自工作线程/协调器处理。
func routeTasks(handlers map[string]jobsapp.Handler) jobsapp.Handler {
	return func(ctx context.Context, task jobsdomain.Task) (jobsports.Completion, error) {
		handler := handlers[task.Type]
		if handler == nil {
			return jobsports.Completion{}, fmt.Errorf("任务类型未注册处理器: %w", jobsdomain.ErrValidation)
		}
		return handler(ctx, task)
	}
}
