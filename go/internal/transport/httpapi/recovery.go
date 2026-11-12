package httpapi

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"errors"
	"net/http"
	"net/url"
	"strconv"

	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
)

func recoveryError(response http.ResponseWriter, err error) int {
	switch {
	case errors.Is(err, recoverydomain.ErrValidation):
		return fail(response, 400, "VALIDATION_ERROR", "恢复参数无效")
	case errors.Is(err, recoverydomain.ErrMissing):
		return fail(response, 404, "NOT_FOUND", "恢复记录不存在")
	case errors.Is(err, recoverydomain.ErrConflict):
		return fail(response, 409, "CONFLICT", "恢复状态或执行轮次冲突")
	default:
		return applicationError(response, err)
	}
}

func (application *Application) serveRecovery(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	create := len(resource) == 1 && resource[0] == "recoveries"
	results := len(resource) == 3 && resource[0] == "tasks" && resource[2] == "recovery-results"
	placeholder := len(resource) == 3 && resource[0] == "comics" && resource[2] == "recovery-placeholder"
	if !create && !results && !placeholder {
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
	parameters, err := url.ParseQuery(request.URL.RawQuery)
	if err != nil || request.URL.ForceQuery || (!results && request.URL.RawQuery != "") {
		return fail(response, 400, "VALIDATION_ERROR", "查询参数无效"), true
	}
	after, limit := "", 20
	for key, values := range parameters {
		if !results || len(values) != 1 || values[0] == "" {
			return fail(response, 400, "VALIDATION_ERROR", "恢复结果仅接受单值游标和数量参数"), true
		}
		switch key {
		case "after":
			after = values[0]
		case "limit":
			limit, err = strconv.Atoi(values[0])
			if err != nil {
				return fail(response, 400, "VALIDATION_ERROR", "数量无效"), true
			}
		default:
			return fail(response, 400, "VALIDATION_ERROR", "查询参数无效"), true
		}
	}
	if application.Recovery == nil || application.Jobs == nil {
		return fail(response, 503, "UNAVAILABLE", "扫盘恢复暂不可用"), true
	}
	if create {
		var body *httprequest.Empty
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		if body == nil {
			return fail(response, 400, "VALIDATION_ERROR", "需要空 JSON 对象"), true
		}
		task, err := application.Recovery.Submit(request.Context(), request.Header.Get("Idempotency-Key"))
		if err != nil {
			return recoveryError(response, err), true
		}
		application.Jobs.Wake()
		response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
		writeJSON(response, 202, DataResponse[jobsdomain.Task]{Data: task})
		return 202, true
	}
	if placeholder {
		value, err := application.Recovery.Placeholder(request.Context(), resource[1])
		if err != nil {
			return recoveryError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	value, err := application.Recovery.Results(request.Context(), resource[1], after, limit)
	if err != nil {
		return recoveryError(response, err), true
	}
	writeData(response, value)
	return 200, true
}
