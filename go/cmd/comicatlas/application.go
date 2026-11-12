package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	exportapp "comicatlas.local/backend/internal/modules/exporting/application"
	exportfiles "comicatlas.local/backend/internal/modules/exporting/infrastructure/filesystem"
	exportsqlite "comicatlas.local/backend/internal/modules/exporting/infrastructure/sqlite"
	ingestionapp "comicatlas.local/backend/internal/modules/ingestion/application"
	ingestionsqlite "comicatlas.local/backend/internal/modules/ingestion/infrastructure/sqlite"
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	jobssqlite "comicatlas.local/backend/internal/modules/jobs/infrastructure/sqlite"
	libraryapp "comicatlas.local/backend/internal/modules/library/application"
	librarysqlite "comicatlas.local/backend/internal/modules/library/infrastructure/sqlite"
	lifecycleapp "comicatlas.local/backend/internal/modules/lifecycle/application"
	lifecyclefiles "comicatlas.local/backend/internal/modules/lifecycle/infrastructure/filesystem"
	lifecyclesqlite "comicatlas.local/backend/internal/modules/lifecycle/infrastructure/sqlite"
	maintenanceapp "comicatlas.local/backend/internal/modules/maintenance/application"
	maintenancesqlite "comicatlas.local/backend/internal/modules/maintenance/infrastructure/sqlite"
	mediaapp "comicatlas.local/backend/internal/modules/media/application"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	mediaffmpeg "comicatlas.local/backend/internal/modules/media/infrastructure/ffmpeg"
	mediafiles "comicatlas.local/backend/internal/modules/media/infrastructure/filesystem"
	mediaimaging "comicatlas.local/backend/internal/modules/media/infrastructure/imaging"
	mediasqlite "comicatlas.local/backend/internal/modules/media/infrastructure/sqlite"
	mediatools "comicatlas.local/backend/internal/modules/media/infrastructure/tools"
	readingapp "comicatlas.local/backend/internal/modules/reading/application"
	readingsqlite "comicatlas.local/backend/internal/modules/reading/infrastructure/sqlite"
	recoveryapp "comicatlas.local/backend/internal/modules/recovery/application"
	recoveryfiles "comicatlas.local/backend/internal/modules/recovery/infrastructure/filesystem"
	recoverysqlite "comicatlas.local/backend/internal/modules/recovery/infrastructure/sqlite"
	schedulerapp "comicatlas.local/backend/internal/modules/scheduler/application"
	schedulersqlite "comicatlas.local/backend/internal/modules/scheduler/infrastructure/sqlite"
	settingsapp "comicatlas.local/backend/internal/modules/settings/application"
	settingssqlite "comicatlas.local/backend/internal/modules/settings/infrastructure/sqlite"
	storageapp "comicatlas.local/backend/internal/modules/storage/application"
	storagefiles "comicatlas.local/backend/internal/modules/storage/infrastructure/filesystem"
	storagesqlite "comicatlas.local/backend/internal/modules/storage/infrastructure/sqlite"
	"comicatlas.local/backend/internal/platform/config"
	"comicatlas.local/backend/internal/platform/persistence"
	"comicatlas.local/backend/internal/platform/process"
	"comicatlas.local/backend/internal/transport/httpapi"
)

func runServer(configuration config.Config) error {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: configuration.LogLevel}))
	slog.SetDefault(logger)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	// 大型库升级时的整库外键检查在 Windows 绑定挂载上可能超过 10 秒，启动窗口需留足余量。
	startup, cancel := context.WithTimeout(ctx, 2*time.Minute)
	database, err := persistence.Open(startup, configuration.DataDirectory, configuration.BusyTimeout)
	cancel()
	if err != nil {
		return err
	}
	defer database.Close()
	if err = persistence.RejectRetiredSharedCatalog(ctx, database); err != nil {
		return err
	}
	books := libraryapp.New(librarysqlite.New(database))
	queue := jobsapp.NewStore(jobssqlite.New(database))
	runner, err := process.New(1, 64<<10)
	if err != nil {
		return err
	}
	video := mediaapp.New(mediaffmpeg.New(runner, configuration.FFprobePath))
	directories, err := ingestionapp.New(configuration.ImportDirectory, configuration.MediaDirectory, queue, books, video)
	if err != nil {
		return err
	}
	defer directories.Close()
	if err = directories.SetHostRoot(configuration.ImportHostRoot); err != nil {
		return fmt.Errorf("IMPORT_HOST_ROOT 必须为有效的宿主机绝对路径: %w", err)
	}
	lifecycleFiles, err := lifecyclefiles.New(configuration.MediaDirectory)
	if err != nil {
		return err
	}
	defer lifecycleFiles.Close()
	images := mediaapp.NewImages(mediaimaging.New(runner, configuration.CwebpPath, configuration.DjpegPath, configuration.ImagePixels))
	cleanupContext, stopCleanup := context.WithTimeout(ctx, 3*time.Second)
	err = mediafiles.CleanStartup(cleanupContext, configuration.MediaDirectory)
	stopCleanup()
	if err != nil {
		return fmt.Errorf("媒体临时工作区启动清理失败: %w", err)
	}
	analysis, err := startAnalysis(ctx, database, configuration, images, logger)
	if err != nil {
		return err
	}
	defer analysis.Close()
	lqFiles, err := mediafiles.New(configuration.MediaDirectory, images)
	if err != nil {
		return err
	}
	defer lqFiles.Close()
	coverService := mediaapp.NewCovers(mediasqlite.New(database), lqFiles, mediaffmpeg.NewFrameExtractor(runner, configuration.FFmpegPath))
	directories.SetCoverGenerator(coverService)
	lifecycleRepository := lifecyclesqlite.New(database, librarysqlite.NormalizeStructure, librarysqlite.NormalizeMedia)
	lifecycle := lifecycleapp.New(lifecycleRepository, lifecycleFiles, queue, coverService, configuration.TrashRetentionDays)
	trashCovers := lifecycleapp.NewCovers(lifecycleRepository, lifecyclefiles.NewCoverReader(lifecycleFiles))
	uploads := mediaapp.NewUploads(mediasqlite.New(database), lqFiles, mediadomain.UploadLimits{MinFreeBytes: configuration.UploadMinFreeBytes, MinFreePercent: configuration.UploadMinFreePercent})
	lq := mediaapp.NewLQ(mediasqlite.New(database), lqFiles, queue)
	hq := mediaapp.NewHQ(mediasqlite.New(database), lqFiles, queue)
	videoProbe := mediaffmpeg.New(runner, configuration.FFprobePath)
	encoderMode, err := mediaffmpeg.ParseEncoderMode(configuration.TranscodeEncoder)
	if err != nil {
		return err
	}
	uploadPublishing := mediaapp.NewUploadPublishing(uploads, mediasqlite.New(database), mediafiles.NewUploadPublisher(lqFiles, videoProbe), queue)
	transcode := mediaapp.NewTranscode(mediasqlite.New(database), mediafiles.NewVideo(lqFiles, mediaffmpeg.NewEncoder(runner, configuration.FFmpegPath, videoProbe, encoderMode, configuration.TranscodeNVENCQuality), videoProbe), queue)
	storageScanner, err := storagefiles.New(configuration.MediaDirectory)
	if err != nil {
		return err
	}
	defer storageScanner.Close()
	storage := storageapp.New(storagesqlite.New(database), storageScanner, queue, storagefiles.NewCapacity(configuration.MediaDirectory))
	metadataFiles := storagefiles.NewMetadata(storageScanner, video)
	metadata := storageapp.NewMetadata(storagesqlite.New(database), metadataFiles, books, queue)
	sidecars := storageapp.NewSidecars(storagesqlite.New(database), metadataFiles, books, queue, coverService)
	maintenance := maintenanceapp.New(maintenancesqlite.New(database), books, queue, maintenanceapp.NewDispatcher(maintenanceapp.CommandServices{LQ: lq, Metadata: metadata, Transcode: transcode, HQ: hq, Trash: lifecycle, Restore: lifecycle, Purge: lifecycle}))
	scans := ingestionapp.NewScans(ingestionsqlite.NewScans(database), directories, queue)
	exportStorage, err := exportfiles.New(configuration.MediaDirectory)
	if err != nil {
		return err
	}
	defer exportStorage.Close()
	exportRepository := exportsqlite.New(database)
	exports := exportapp.New(exportRepository, exportStorage, queue)
	directoryMoves := exportapp.NewDirectoryExecution(exportRepository, exportStorage, coverService, queue)
	directoryPreviews := exportapp.NewDirectoryConfirmation(exportRepository, queue)
	directoryPreflight := exportapp.NewDirectoryPreflight(exportapp.NewDirectoryPlanner(exportRepository, exportStorage), exportRepository, coverService, queue)
	recoveryFiles, err := recoveryfiles.New(configuration.MediaDirectory)
	if err != nil {
		return err
	}
	defer recoveryFiles.Close()
	recovery := recoveryapp.New(recoverysqlite.New(database), recoveryfiles.NewVideo(recoveryFiles, mediaapp.NewOpenedInspector(videoProbe)), queue)
	repair := recoveryapp.NewRepair(recoverysqlite.New(database), recoveryfiles.NewVideo(recoveryFiles, mediaapp.NewOpenedInspector(videoProbe)), queue)
	handler := routeTasks(map[string]jobsapp.Handler{
		"directory_import":    directories.Run,
		"archive_import":      directories.Run,
		"recovery_repair":     repair.Run,
		"disk_recovery":       recovery.Run,
		"directory_preflight": directoryPreflight.Run,
		"directory_move":      directoryMoves.Run,
		"metadata_batch":      maintenance.Run,
		"metadata_sync":       sidecars.Run,
		"lifecycle_audit":     lifecycle.RunAudit,
		"lifecycle":           lifecycle.Run,
		"metadata_refresh":    metadata.Run,
		"media_upload":        uploadPublishing.Run,
		"video_transcode":     transcode.Run,
		"archive_export":      exports.Run,
		"directory_scan":      scans.Run,
		"hq_release":          hq.Run,
		"storage_refresh":     storage.Run,
		"image_lq":            lq.Run,
	})
	engine, err := jobsapp.Start(ctx, queue, handler, func(err error) {
		logger.Warn("后台任务执行或状态提交失败", "diagnostic", err, "completionStage", jobsdomain.CompletionStage(err), "storageClass", persistence.FailureClass(err))
	})
	if err != nil {
		return err
	}
	defer engine.Close()
	importBatches := ingestionapp.NewImportBatchCoordinator(scans, engine.Cancel, engine.Wake)
	commandContext, stopCommands := context.WithCancel(ctx)
	commandsDone := make(chan struct{})
	go func() {
		defer close(commandsDone)
		maintenanceapp.NewCoordinator(maintenance, engine.Cancel, engine.Wake).Maintain(commandContext, func(error) { logger.Warn("批量资源协调暂未完成，请检查父任务状态") })
	}()
	defer func() { stopCommands(); <-commandsDone }()
	importBatchContext, stopImportBatches := context.WithCancel(ctx)
	importBatchesDone := make(chan struct{})
	go func() {
		defer close(importBatchesDone)
		importBatches.Maintain(importBatchContext, func(error) { logger.Warn("批量导入协调暂未完成，将自动重试") })
	}()
	defer func() { stopImportBatches(); <-importBatchesDone }()
	sidecarContext, stopSidecars := context.WithCancel(ctx)
	sidecarsDone := make(chan struct{})
	go func() {
		defer close(sidecarsDone)
		sidecars.Maintain(sidecarContext, engine.Wake, func(error) { logger.Warn("自动侧车调度暂未完成，将自动重试") })
	}()
	defer func() { stopSidecars(); <-sidecarsDone }()
	scheduler := schedulerapp.New(schedulersqlite.New(database))
	for _, job := range []schedulerapp.Job{
		{
			ID:              "storage-metrics",
			Title:           "存储占用统计",
			Description:     "重算有变更的漫画占用指标；不扫描媒体卷，实际占用请在存储统计页扫描。",
			Unit:            "部漫画",
			DefaultInterval: 4 * time.Hour,
			DefaultEnabled:  true,
			Timeout:         5 * time.Minute,
			RunAtStartup:    true,
			Run:             storage.RefreshAllMetrics,
			ShouldRun:       storagesqlite.New(database).MetricsDirty,
		},
		{
			ID:              "storage-scan",
			Title:           "媒体卷扫描",
			Description:     "投递一次媒体卷实际占用扫描。已有扫描时跳过，扫描进度与最终结果见任务中心。",
			Unit:            "个扫描任务",
			DefaultInterval: 7 * 24 * time.Hour,
			Timeout:         5 * time.Second,
			Run: func(ctx context.Context) (int, error) {
				count, err := storage.ScheduleRefresh(ctx)
				if count > 0 {
					engine.Wake()
				}
				return count, err
			},
		},
		{
			ID:              "upload-cleanup",
			Title:           "上传残留清理",
			Description:     "回收过期或已取消上传会话中待清理的文件。",
			Unit:            "个会话",
			DefaultInterval: time.Minute,
			DefaultEnabled:  true,
			Timeout:         30 * time.Second,
			RunAtStartup:    true,
			Run:             uploads.CleanupPending,
		},
		{
			ID:              "export-cleanup",
			Title:           "取消导出清理",
			Description:     "回收已取消且未进入发布阶段的归档导出暂存目录。",
			Unit:            "个目录",
			DefaultInterval: 15 * time.Minute,
			DefaultEnabled:  true,
			Timeout:         30 * time.Second,
			RunAtStartup:    true,
			Run:             exports.CleanupCancelled,
		},
	} {
		if err = scheduler.Register(job); err != nil {
			return err
		}
	}
	if err = scheduler.Start(ctx, func(err error) {
		logger.Warn("定时器调度失败", "error", err)
	}); err != nil {
		return err
	}
	defer scheduler.Close()
	engine.ObserveCompletion(func() { maintenance.WakeCommands(); importBatches.Wake(); scheduler.Notify("storage-metrics") })
	application := &httpapi.Application{UploadPublishing: uploadPublishing, Uploads: uploads, Transcode: transcode, Exports: exports, Scans: scans, HQ: hq, Storage: storage, Scheduler: scheduler, Library: books, Jobs: engine, Directory: directories, LQ: lq, ToolCapabilities: mediaapp.NewCapabilities(mediatools.NewCapabilityReader(runner, configuration.FFmpegPath, configuration.FFprobePath, configuration.CwebpPath, configuration.DjpegPath)), Reading: readingapp.New(readingsqlite.New(database)), Settings: settingsapp.New(settingssqlite.New(database))}
	application.Covers = coverService
	application.Analysis = analysis.service
	application.AnalysisJobs = analysis.engine
	application.AnalysisStore = analysis.store
	application.Metadata = metadata
	application.Recovery = recovery
	application.RecoveryRepair = repair
	application.Sidecars = sidecars
	application.Maintenance = maintenance
	application.Lifecycle = lifecycle
	application.TrashCovers = trashCovers
	application.DirectoryPreviews = directoryPreviews
	application.DirectoryPreflight = directoryPreflight
	application.DirectoryMoves = directoryMoves
	endpoints, err := listenServers(configuration.HTTPAddress, configuration.ReadingHTTPAddress, httpapi.New(database, logger, application), httpapi.NewReading(database, logger, application))
	if err != nil {
		return err
	}
	return serveServers(ctx, endpoints, configuration.ShutdownTimeout, logger)
}
