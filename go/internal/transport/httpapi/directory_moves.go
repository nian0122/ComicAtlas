package httpapi

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"net/http"
	"net/url"
)

func (application *Application) serveDirectoryMoves(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	create := len(resource) == 1 && resource[0] == "directory-move-previews"
	status := len(resource) == 2 && resource[0] == "directory-move-previews"
	confirm := len(resource) == 3 && resource[0] == "directory-move-previews" && resource[2] == "confirm"
	results := len(resource) == 3 && resource[0] == "tasks" && resource[2] == "directory-move-results"
	if !create && !confirm && !results && !status {
		return 0, false
	}
	method := http.MethodPost
	if results || status {
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
	if application.DirectoryPreviews == nil || application.DirectoryPreflight == nil || application.DirectoryMoves == nil || application.Jobs == nil {
		return fail(response, 503, "UNAVAILABLE", "文件夹移出暂不可用"), true
	}
	if create {
		var body exportdomain.DirectoryPreviewRequest
		if !decodeBodyLimit(response, request, &body, 2<<20) {
			return 400, true
		}
		value, err := application.DirectoryPreflight.Create(request.Context(), body.ComicIDs)
		if err != nil {
			return applicationError(response, err), true
		}
		application.Jobs.Wake()
		response.Header().Set("Location", "/api/v1/directory-move-previews/"+value.ID)
		writeJSON(response, 202, DataResponse[exportdomain.DirectoryPreflightView]{Data: value})
		return 202, true
	}
	if status {
		value, err := application.DirectoryPreflight.Status(request.Context(), resource[1])
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	if confirm {
		var body exportdomain.DirectoryConfirmRequest
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		if err := exportdomain.ValidateDirectoryConsent(body); err != nil {
			return applicationError(response, err), true
		}
		task, err := application.DirectoryPreviews.Submit(request.Context(), resource[1], body.ConfirmationToken, request.Header.Get("Idempotency-Key"))
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
