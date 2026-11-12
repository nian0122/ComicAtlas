package httpapi

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"net/http"
)

func (application *Application) serveExports(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	create := len(resource) == 3 && resource[0] == "comics" && resource[2] == "exports"
	artifacts := len(resource) == 3 && resource[0] == "tasks" && resource[2] == "artifacts"
	if !create && !artifacts {
		return 0, false
	}
	method := "GET"
	if create {
		method = "POST"
	}
	if request.Method != method {
		response.Header().Set("Allow", method)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if application.Exports == nil {
		return fail(response, 503, "UNAVAILABLE", "归档导出暂不可用"), true
	}
	if request.URL.RawQuery != "" {
		return fail(response, 400, "VALIDATION_ERROR", "此接口不接受查询参数"), true
	}
	if create {
		var body exportdomain.Request
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		task, err := application.Exports.Submit(request.Context(), resource[1], body, request.Header.Get("Idempotency-Key"))
		if err != nil {
			return applicationError(response, err), true
		}
		application.Jobs.Wake()
		response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
		writeJSON(response, 202, DataResponse[jobsdomain.Task]{Data: task})
		return 202, true
	}
	view, err := application.Exports.Artifacts(request.Context(), resource[1])
	if err != nil {
		return applicationError(response, err), true
	}
	writeData(response, view)
	return 200, true
}
