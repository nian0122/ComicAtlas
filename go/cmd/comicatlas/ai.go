package main

import (
	"context"
	"log/slog"
	"time"

	aiapp "comicatlas.local/backend/internal/modules/ai/application"
	aifiles "comicatlas.local/backend/internal/modules/ai/infrastructure/filesystem"
	aihttp "comicatlas.local/backend/internal/modules/ai/infrastructure/httpclient"
	aisqlite "comicatlas.local/backend/internal/modules/ai/infrastructure/sqlite"
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobssqlite "comicatlas.local/backend/internal/modules/jobs/infrastructure/sqlite"
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	"comicatlas.local/backend/internal/platform/config"
	"comicatlas.local/backend/internal/platform/persistence"
)

type analysisRuntime struct {
	service *aiapp.Tasks
	store   *jobsapp.Store
	engine  *jobsapp.Engine
	client  *aihttp.Client
}

func (runtime *analysisRuntime) Close() {
	if runtime.engine != nil {
		runtime.engine.Close()
	}
	if runtime.client != nil {
		runtime.client.Close()
	}
}

func startAnalysis(ctx context.Context, database *persistence.Database, configuration config.Config, images mediaports.ImageRenderer, logger *slog.Logger) (runtime *analysisRuntime, err error) {
	repository := aisqlite.New(database)
	runtime = &analysisRuntime{store: jobsapp.NewStore(jobssqlite.NewAnalysis(database))}
	runtime.service = aiapp.NewTasks(repository, nil, nil, runtime.store)
	cleanupContext, stopCleanup := context.WithTimeout(ctx, 3*time.Second)
	cleanupErr := aifiles.CleanStartup(cleanupContext, configuration.MediaDirectory)
	stopCleanup()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if cleanupErr != nil {
		logger.Warn("AI 临时样本清理未完成，本次不启动 AI 工作线程")
	}
	if configuration.AIEndpoint != "" && cleanupErr == nil {
		preparer, prepareErr := aifiles.New(configuration.MediaDirectory, images)
		if prepareErr != nil {
			logger.Warn("AI 图片准备组件不可用，本次不启动 AI 工作线程")
		} else {
			runtime.client, err = aihttp.New(configuration.AIEndpoint, configuration.AIRequestTimeout)
			if err != nil {
				return nil, err
			}
			runtime.service = aiapp.NewTasks(repository, aiapp.NewSampler(preparer), runtime.client, runtime.store)
			runtime.engine, err = jobsapp.Start(ctx, runtime.store, runtime.service.Run, func(error) { logger.Warn("AI 后台任务未完成，请查看任务的固定错误摘要") })
			if err != nil {
				runtime.client.Close()
				return nil, err
			}
			return runtime, nil
		}
	}
	if err = runtime.store.Recover(ctx); err != nil {
		return nil, err
	}
	return runtime, nil
}
