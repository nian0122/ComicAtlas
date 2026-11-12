package httpapi

import (
	aiapp "comicatlas.local/backend/internal/modules/ai/application"
	exportapp "comicatlas.local/backend/internal/modules/exporting/application"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	lifecycleapp "comicatlas.local/backend/internal/modules/lifecycle/application"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	maintenanceapp "comicatlas.local/backend/internal/modules/maintenance/application"
	recoveryapp "comicatlas.local/backend/internal/modules/recovery/application"
	schedulerapp "comicatlas.local/backend/internal/modules/scheduler/application"
	storageapp "comicatlas.local/backend/internal/modules/storage/application"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	ingestionapp "comicatlas.local/backend/internal/modules/ingestion/application"
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	libraryapp "comicatlas.local/backend/internal/modules/library/application"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediaapp "comicatlas.local/backend/internal/modules/media/application"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	readingapp "comicatlas.local/backend/internal/modules/reading/application"
	settingsapp "comicatlas.local/backend/internal/modules/settings/application"
	settingsdomain "comicatlas.local/backend/internal/modules/settings/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
)

type Application struct {
	Analysis           *aiapp.Tasks
	AnalysisJobs       *jobsapp.Engine
	AnalysisStore      *jobsapp.Store
	Recovery           *recoveryapp.Service
	RecoveryRepair     *recoveryapp.Repair
	Maintenance        *maintenanceapp.Service
	Sidecars           *storageapp.Sidecars
	TrashCovers        *lifecycleapp.Covers
	Lifecycle          *lifecycleapp.Service
	Metadata           *storageapp.MetadataService
	Covers             *mediaapp.Covers
	UploadPublishing   *mediaapp.UploadPublisher
	Uploads            *mediaapp.UploadService
	Transcode          *mediaapp.TranscodeService
	Exports            *exportapp.Service
	DirectoryPreviews  *exportapp.DirectoryConfirmation
	DirectoryPreflight *exportapp.DirectoryPreflight
	DirectoryMoves     *exportapp.DirectoryExecution
	Scans              *ingestionapp.ScanService
	HQ                 *mediaapp.HQService
	Storage            *storageapp.Service
	Scheduler          *schedulerapp.Manager
	LQ                 *mediaapp.LQService
	ToolCapabilities   *mediaapp.Capabilities
	Library            *libraryapp.Service
	Jobs               *jobsapp.Engine
	Directory          *ingestionapp.Directory
	Reading            *readingapp.Service
	Settings           *settingsapp.Service
}

func (application *Application) Serve(response http.ResponseWriter, request *http.Request) int {
	parts := strings.Split(strings.Trim(request.URL.Path, "/"), "/")
	if len(parts) < 3 || parts[0] != "api" || parts[1] != "v1" {
		return fail(response, http.StatusNotFound, "NOT_FOUND", "资源不存在")
	}
	resource := parts[2:]
	if status, handled := application.serveTaskEvents(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveToolCapabilities(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveAnalysis(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveRecoveryRepair(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveRecovery(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveDirectoryMoves(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveReactionManagement(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveBatches(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveSidecars(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveTrashCover(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveLifecycleAudit(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveLifecycle(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveMetadataRefresh(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveCoverSelection(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveCoverFile(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveUploads(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveTranscode(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveExports(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveTaskQueries(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveScans(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveSchedules(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveStorage(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveHQ(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveLQ(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveVisualPreviews(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveStructure(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveMetadata(response, request, resource); handled {
		return status
	}
	if status, handled := application.serveReading(response, request, resource); handled {
		return status
	}
	method := func(allowed ...string) bool {
		for _, candidate := range allowed {
			if request.Method == candidate {
				return true
			}
		}
		response.Header().Set("Allow", strings.Join(allowed, ", "))
		fail(response, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "请求方法不受支持")
		return false
	}
	send := func(value any, err error) int {
		if err != nil {
			return applicationError(response, err)
		}
		writeData(response, value)
		return http.StatusOK
	}
	ctx := request.Context()
	if len(resource) == 1 && resource[0] == "comics" {
		if !method("GET") {
			return 405
		}
		page, size, err := pagination(request)
		if err != nil {
			return fail(response, 400, "VALIDATION_ERROR", "分页参数无效")
		}
		keyword := request.URL.Query().Get("keyword")
		if len(keyword) > 500 {
			return fail(response, 400, "VALIDATION_ERROR", "检索条件过长")
		}
		parameters := request.URL.Query()
		result, err := application.Library.Search(ctx, librarydomain.ListQuery{Page: page, PageSize: size, Keyword: keyword, CategoryID: parameters.Get("categoryId"), TagIDs: parameters["tagId"], ReadStatus: parameters.Get("readStatus"), Sort: parameters.Get("sort"), Order: parameters.Get("order")})
		return send(result, err)
	}
	if len(resource) == 2 && resource[0] == "comics" {
		if !method("GET", "PUT") {
			return 405
		}
		if request.Method == "PUT" {
			var body librarydomain.MetadataUpdate
			if !decodeBodyLimit(response, request, &body, 128<<10) {
				return 400
			}
			result, err := application.Library.UpdateMetadata(ctx, resource[1], body)
			return send(result, err)
		}
		result, err := application.Library.Detail(ctx, resource[1])
		return send(result, err)
	}
	if len(resource) == 3 && resource[0] == "chapters" && resource[2] == "reader" {
		if !method("GET") {
			return 405
		}
		result, err := application.Library.Reader(ctx, resource[1])
		return send(result, err)
	}
	if len(resource) == 3 && resource[0] == "comics" && resource[2] == "reading-progress" {
		if !method("GET", "PUT") {
			return 405
		}
		if request.Method == "GET" {
			result, err := application.Library.Progress(ctx, resource[1])
			return send(result, err)
		}
		var body httprequest.ReadingProgress
		if !decodeBody(response, request, &body) {
			return 400
		}
		if body.MediaID != "" {
			if body.PageNumber != 0 {
				return fail(response, 400, "VALIDATION_ERROR", "mediaId 与 pageNumber 二选一")
			}
			result, err := application.Library.SaveMediaProgress(ctx, resource[1], body.ChapterID, body.MediaID)
			return send(result, err)
		}
		result, err := application.Library.SaveProgress(ctx, resource[1], body.ChapterID, body.PageNumber)
		return send(result, err)
	}
	if len(resource) == 2 && resource[0] == "imports" && (resource[1] == "directories" || resource[1] == "archives") {
		if !method("POST") {
			return 405
		}
		var body httprequest.Import
		if !decodeBody(response, request, &body) {
			return 400
		}
		task, err := application.Directory.Submit(ctx, resource[1] == "archives", body.SourcePath, body.Title, request.Header.Get("Idempotency-Key"))
		if err != nil {
			return applicationError(response, err)
		}
		application.Jobs.Wake()
		response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
		writeJSON(response, 202, struct {
			Data jobsdomain.Task `json:"data"`
		}{task})
		return 202
	}
	if len(resource) == 2 && resource[0] == "tasks" {
		if !method("GET") {
			return 405
		}
		task, err := application.Jobs.Store.Inspect(ctx, resource[1])
		return send(task, err)
	}
	if len(resource) == 3 && resource[0] == "tasks" && (resource[2] == "cancel" || resource[2] == "retry") {
		if !method("POST") {
			return 405
		}
		inspected, inspectErr := application.Jobs.Store.Inspect(ctx, resource[1])
		if inspectErr != nil {
			return applicationError(response, inspectErr)
		}
		if inspected.Type == "ai_analysis" {
			return application.analysisAction(response, request, inspected, resource[2])
		}
		var err error
		if resource[2] == "cancel" {
			err = application.Jobs.Cancel(ctx, resource[1])
		} else {
			task := inspected
			if task.Type == "recovery_repair" {
				if application.RecoveryRepair == nil {
					return fail(response, 503, "UNAVAILABLE", "占位补全暂不可用")
				}
				err = application.RecoveryRepair.Retry(ctx, resource[1])
				if err != nil {
					return recoveryError(response, err)
				}
			} else if task.Type == "directory_move" {
				if application.DirectoryMoves == nil {
					return fail(response, 503, "UNAVAILABLE", "文件夹移出暂不可用")
				}
				err = application.DirectoryMoves.Retry(ctx, resource[1])
			} else if task.Type == "directory_preflight" {
				if application.DirectoryPreflight == nil {
					return fail(response, 503, "UNAVAILABLE", "文件夹移出预检暂不可用")
				}
				err = application.DirectoryPreflight.Retry(ctx, resource[1])
			} else if task.Type == "command_batch" {
				if application.Maintenance == nil {
					return fail(response, 503, "UNAVAILABLE", "批量维护暂不可用")
				}
				err = application.Maintenance.RetryCommand(ctx, resource[1])
			} else if task.Type == "metadata_sync" {
				if application.Sidecars == nil {
					return fail(response, 503, "UNAVAILABLE", "自动侧车服务暂不可用")
				}
				err = application.Sidecars.Retry(ctx, resource[1])
			} else {
				err = application.Jobs.Store.Retry(ctx, resource[1])
			}
			if err == nil {
				application.Jobs.Wake()
			}
		}
		if err != nil {
			return applicationError(response, err)
		}
		task, err := application.Jobs.Store.Inspect(ctx, resource[1])
		return send(task, err)
	}
	if len(resource) == 3 && resource[0] == "media" && resource[2] == "content" {
		if !method("GET", "HEAD") {
			return 405
		}
		quality := request.URL.Query().Get("quality")
		if len(request.URL.Query()["quality"]) > 1 || (quality != "" && quality != "hq" && quality != "auto" && quality != "lq") {
			return fail(response, 400, "VALIDATION_ERROR", "阅读质量无效")
		}
		var file *os.File
		var contentType string
		var err error
		if (quality == "lq" || quality == "auto") && application.LQ != nil {
			file, err = application.LQ.Open(ctx, resource[1])
			if err == nil {
				contentType = "image/webp"
			}
		}
		if file == nil && quality == "lq" {
			if err == nil {
				err = mediadomain.ErrLQNotFound
			}
			return applicationError(response, err)
		}
		if file == nil {
			if err != nil && !errors.Is(err, mediadomain.ErrLQNotFound) {
				return applicationError(response, err)
			}
			file, contentType, err = application.Directory.OpenMedia(ctx, resource[1])
		}
		if err != nil {
			return applicationError(response, err)
		}
		defer file.Close()
		info, err := file.Stat()
		if err != nil {
			return applicationError(response, err)
		}
		response.Header().Set("Content-Type", contentType)
		response.Header().Set("Cache-Control", "private, no-cache")
		// 以登记文件的大小和修改时间生成条件缓存标识；内容替换后自然失效。
		response.Header().Set("ETag", `"`+strconv.FormatInt(info.Size(), 16)+"-"+strconv.FormatInt(info.ModTime().UnixNano(), 16)+`"`)
		tracked := &statusWriter{ResponseWriter: response, status: 200, writeIdleTimeout: 30 * time.Second}
		http.ServeContent(tracked, request, info.Name(), info.ModTime(), file)
		return tracked.status
	}
	return fail(response, 404, "NOT_FOUND", "资源不存在")
}

type statusWriter struct {
	http.ResponseWriter
	status           int
	writeIdleTimeout time.Duration
	writeDeadlineErr error
}

func (writer *statusWriter) Unwrap() http.ResponseWriter { return writer.ResponseWriter }

func (writer *statusWriter) WriteHeader(status int) {
	writer.status = status
	writer.writeDeadlineErr = writer.refreshWriteDeadline()
	writer.ResponseWriter.WriteHeader(status)
}

func (writer *statusWriter) Write(data []byte) (int, error) {
	if writer.writeDeadlineErr != nil {
		return 0, writer.writeDeadlineErr
	}
	if err := writer.refreshWriteDeadline(); err != nil {
		return 0, err
	}
	return writer.ResponseWriter.Write(data)
}

func (writer *statusWriter) refreshWriteDeadline() error {
	if writer.writeIdleTimeout <= 0 {
		return nil
	}
	err := http.NewResponseController(writer).SetWriteDeadline(time.Now().Add(writer.writeIdleTimeout))
	if errors.Is(err, http.ErrNotSupported) {
		return nil
	}
	return err
}
func fail(response http.ResponseWriter, status int, code, message string) int {
	writeError(response, status, code, message)
	return status
}
func applicationError(response http.ResponseWriter, err error) int {
	switch {
	case errors.Is(err, lifecycledomain.ErrConfirmation):
		return fail(response, 409, "CONFIRMATION_REQUIRED", "清理确认已过期、已使用或与当前范围及保留期不符，请重新预览")
	case errors.Is(err, lifecycledomain.ErrNotFound):
		return fail(response, 404, "NOT_FOUND", "回收对象不存在")
	case errors.Is(err, lifecycledomain.ErrPurged):
		return fail(response, 409, "AUDIT_UNAVAILABLE", "对象已永久清理，文件清单已删除")
	case errors.Is(err, lifecycledomain.ErrRestored):
		return fail(response, 409, "AUDIT_UNAVAILABLE", "对象已还原，回收文件清单已清理")
	case errors.Is(err, lifecycledomain.ErrValidation):
		return fail(response, 400, "VALIDATION_ERROR", "回收参数无效")
	case errors.Is(err, lifecycledomain.ErrConflict):
		return fail(response, 409, "CONFLICT", "生命周期状态、并发任务或恢复位置冲突")
	case errors.Is(err, mediadomain.ErrUploadNotFound):
		return fail(response, 404, "NOT_FOUND", "上传会话、文件或目标不存在")
	case errors.Is(err, mediadomain.ErrUploadValidation):
		return fail(response, 400, "VALIDATION_ERROR", "上传参数或分片范围无效")
	case errors.Is(err, mediadomain.ErrUploadConflict):
		return fail(response, 409, "CONFLICT", "上传会话、目标或分片冲突")
	case errors.Is(err, mediadomain.ErrUploadChecksum):
		return fail(response, 409, "UPLOAD_CHECKSUM_MISMATCH", "分片摘要与声明或已接收内容不一致")
	case errors.Is(err, mediadomain.ErrUploadExpired):
		return fail(response, 410, "UPLOAD_EXPIRED", "上传会话已过期")
	case errors.Is(err, mediadomain.ErrUploadSpace):
		return fail(response, 507, "UPLOAD_CAPACITY", "上传空间或活动会话预算不足")
	case errors.Is(err, mediadomain.ErrUploadIO):
		return fail(response, 500, "UPLOAD_STORAGE_FAILED", "上传存储操作失败，可查询会话后重试")
	case errors.Is(err, exportdomain.ErrNotFound), errors.Is(err, storagedomain.ErrNotFound), errors.Is(err, librarydomain.ErrNotFound), errors.Is(err, jobsdomain.ErrNotFound), errors.Is(err, os.ErrNotExist), errors.Is(err, mediadomain.ErrLQNotFound):
		return fail(response, 404, "NOT_FOUND", "资源不存在")
	case errors.Is(err, mediadomain.ErrTranscodeLimit), errors.Is(err, exportdomain.ErrValidation), errors.Is(err, storagedomain.ErrValidation), errors.Is(err, librarydomain.ErrInvalidProgress), errors.Is(err, jobsdomain.ErrValidation), errors.Is(err, librarydomain.ErrValidation), errors.Is(err, settingsdomain.ErrValidation), errors.Is(err, mediadomain.ErrLQValidation), errors.Is(err, mediadomain.ErrLQEmpty):
		return fail(response, 400, "VALIDATION_ERROR", "参数或阅读位置无效")
	case errors.Is(err, storagedomain.ErrConflict), errors.Is(err, exportdomain.ErrConflict), errors.Is(err, exportdomain.ErrSource), errors.Is(err, jobsdomain.ErrConflict), errors.Is(err, librarydomain.ErrConflict), errors.Is(err, settingsdomain.ErrConflict), errors.Is(err, mediadomain.ErrLQConflict):
		return fail(response, 409, "CONFLICT", "状态、版本、引用或幂等键冲突")
	case errors.Is(err, jobsdomain.ErrFull), errors.Is(err, jobsdomain.ErrStopped):
		return fail(response, 503, "UNAVAILABLE", "后台暂不可受理新任务")
	default:
		return fail(response, 500, "INTERNAL_ERROR", "服务内部错误")
	}
}
func decodeBody(response http.ResponseWriter, request *http.Request, value any) bool {
	return decodeBodyLimit(response, request, value, 16<<10)
}
func decodeBodyLimit(response http.ResponseWriter, request *http.Request, value any, limit int64) bool {
	if strings.Split(request.Header.Get("Content-Type"), ";")[0] != "application/json" {
		fail(response, 400, "VALIDATION_ERROR", "需要 application/json 请求")
		return false
	}
	request.Body = http.MaxBytesReader(response, request.Body, limit)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		fail(response, 400, "VALIDATION_ERROR", "JSON 请求无效或过大")
		return false
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		fail(response, 400, "VALIDATION_ERROR", "只允许一个 JSON 对象")
		return false
	}
	return true
}
func pagination(request *http.Request) (int, int, error) {
	page, size := 1, 20
	var err error
	if value := request.URL.Query().Get("page"); value != "" {
		page, err = strconv.Atoi(value)
		if err != nil {
			return 0, 0, err
		}
	}
	if value := request.URL.Query().Get("pageSize"); value != "" {
		size, err = strconv.Atoi(value)
		if err != nil {
			return 0, 0, err
		}
	}
	if page < 1 || page > 1000000 || size < 1 || size > 100 {
		return 0, 0, errors.New("分页越界")
	}
	return page, size, nil
}
