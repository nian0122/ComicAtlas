package httpapi

import (
	"errors"
	"io"
	"net/http"

	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
)

func analysisError(response http.ResponseWriter, err error) int {
	switch {
	case errors.Is(err, aidomain.ErrValidation):
		return fail(response, 400, "VALIDATION_ERROR", "AI 分析参数无效或超出预算")
	case errors.Is(err, aidomain.ErrNotFound):
		return fail(response, 404, "NOT_FOUND", "AI 分析记录不存在")
	case errors.Is(err, aidomain.ErrSource):
		return fail(response, 409, "CONFLICT", "AI 来源或版本与受理快照不一致，请按当前资料重新提交")
	case errors.Is(err, aidomain.ErrUnavailable), errors.Is(err, aidomain.ErrBusy):
		return fail(response, 503, "UNAVAILABLE", "AI 分析暂不可用")
	default:
		return applicationError(response, err)
	}
}

func analysisNoQuery(response http.ResponseWriter, request *http.Request) bool {
	if request.URL.RawQuery != "" || request.URL.ForceQuery {
		fail(response, 400, "VALIDATION_ERROR", "AI 接口不接受查询参数")
		return false
	}
	return true
}

func analysisNoBody(response http.ResponseWriter, request *http.Request) bool {
	if request.Body == nil {
		return true
	}
	data, err := io.ReadAll(io.LimitReader(request.Body, 1))
	if err != nil || len(data) != 0 {
		fail(response, 400, "VALIDATION_ERROR", "此 AI 接口不接受请求体")
		return false
	}
	return true
}

func (application *Application) serveAnalysis(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	create := len(resource) == 3 && resource[0] == "comics" && resource[2] == "ai-analyses"
	result := len(resource) == 3 && resource[0] == "tasks" && resource[2] == "ai-result"
	if !create && !result {
		return 0, false
	}
	method := http.MethodGet
	if create {
		method = http.MethodPost
	}
	if request.Method != method {
		response.Header().Set("Allow", method)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if !analysisNoQuery(response, request) {
		return 400, true
	}
	if len(resource[1]) > 128 {
		return fail(response, 400, "VALIDATION_ERROR", "对象标识无效"), true
	}
	if application.Analysis == nil {
		return fail(response, 503, "UNAVAILABLE", "AI 分析暂不可用"), true
	}
	if create {
		if application.AnalysisJobs == nil || application.AnalysisStore == nil {
			return fail(response, 503, "UNAVAILABLE", "AI 工作线程尚未启用"), true
		}
		var body *aidomain.AnalysisRequest
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		if body == nil {
			return fail(response, 400, "VALIDATION_ERROR", "需要 AI 分析参数对象"), true
		}
		task, err := application.Analysis.Submit(request.Context(), resource[1], *body, request.Header.Get("Idempotency-Key"))
		if err != nil {
			return analysisError(response, err), true
		}
		if application.AnalysisJobs != nil {
			application.AnalysisJobs.Wake()
		}
		response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
		writeJSON(response, 202, DataResponse[jobsdomain.Task]{Data: task})
		return 202, true
	}
	if !analysisNoBody(response, request) {
		return 400, true
	}
	value, err := application.Analysis.Result(request.Context(), resource[1])
	if err != nil {
		return analysisError(response, err), true
	}
	if value == nil {
		response.Header().Set("Location", "/api/v1/tasks/"+resource[1])
		return fail(response, 409, "AI_RESULT_NOT_READY", "任务尚无已成功提交的候选结果，请查询任务状态"), true
	}
	writeData(response, value)
	return 200, true
}

func (application *Application) analysisAction(response http.ResponseWriter, request *http.Request, task jobsdomain.Task, action string) int {
	if !analysisNoQuery(response, request) || !analysisNoBody(response, request) {
		return 400
	}
	if application.Analysis == nil || application.AnalysisStore == nil {
		return fail(response, 503, "UNAVAILABLE", "AI 分析暂不可用")
	}
	var err error
	if action == "cancel" {
		if application.AnalysisJobs != nil {
			err = application.AnalysisJobs.Cancel(request.Context(), task.ID)
		} else {
			err = application.AnalysisStore.Cancel(request.Context(), task.ID)
		}
	} else {
		err = application.Analysis.Retry(request.Context(), task.ID)
		if err == nil && application.AnalysisJobs != nil {
			application.AnalysisJobs.Wake()
		}
	}
	if err != nil {
		return analysisError(response, err)
	}
	current, err := application.AnalysisStore.Inspect(request.Context(), task.ID)
	if err != nil {
		return applicationError(response, err)
	}
	writeData(response, current)
	return 200
}
