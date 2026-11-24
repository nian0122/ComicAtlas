package httpapi

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"net/http"
	"net/url"
)

func (application *Application) serveDirectoryMoves(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	create := len(resource) == 1 && resource[0] == "directory-moves"
	results := len(resource) == 3 && resource[0] == "tasks" && resource[2] == "directory-move-results"
	if !create && !results {
		return 0, false
	}
	method := http.MethodPost
	if results {
		method = http.MethodGet
	}
	if request.Method != method {
		response.Header().Set("Allow", method)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	parameters, err := url.ParseQuery(request.URL.RawQuery)
	if err != nil || request.URL.ForceQuery || (!results && request.URL.RawQuery != "") {
		return fail(response, 400, "VALIDATION_ERROR", "查询参数无效"), true
	}
	if results {
		for key, values := range parameters {
			if (key != "page" && key != "pageSize") || len(values) != 1 || values[0] == "" {
				return fail(response, 400, "VALIDATION_ERROR", "结果仅接受单值分页参数"), true
			}
		}
	}
	if application.DirectoryMoves == nil || application.Jobs == nil {
		return fail(response, 503, "UNAVAILABLE", "文件夹移出暂不可用"), true
	}
	if create {
		var body exportdomain.DirectoryMoveCreateRequest
		if !decodeBodyLimit(response, request, &body, 2<<20) {
			return 400, true
		}
		task, err := application.DirectoryMoves.Create(request.Context(), body.ComicIDs, request.Header.Get("Idempotency-Key"))
		if err != nil {
			return applicationError(response, err), true
		}
		application.Jobs.Wake()
		response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
		writeJSON(response, 202, DataResponse[jobsdomain.Task]{Data: task})
		return 202, true
	}
	page, size, err := pagination(request)
	if err != nil {
		return fail(response, 400, "VALIDATION_ERROR", "分页参数无效"), true
	}
	value, err := application.DirectoryMoves.Results(request.Context(), resource[1], page, size)
	if err != nil {
		return applicationError(response, err), true
	}
	writeData(response, value)
	return 200, true
}
