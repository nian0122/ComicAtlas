package httpapi

import (
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
	"net/http"
)

func (application *Application) serveSidecars(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	statusRoute := len(resource) == 3 && resource[0] == "comics" && resource[2] == "sidecar"
	syncRoute := len(resource) == 4 && resource[0] == "comics" && resource[2] == "sidecar" && resource[3] == "sync"
	items := len(resource) == 3 && resource[0] == "tasks" && resource[2] == "items"
	if !statusRoute && !syncRoute && !items {
		return 0, false
	}
	if items {
		if application.Jobs == nil {
			return 0, false
		}
		task, err := application.Jobs.Store.Inspect(request.Context(), resource[1])
		if err != nil {
			return applicationError(response, err), true
		}
		if task.Type != "metadata_sync" {
			return 0, false
		}
	}
	method := "GET"
	if syncRoute {
		method = "POST"
	}
	if request.Method != method {
		response.Header().Set("Allow", method)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if application.Sidecars == nil {
		return fail(response, 503, "UNAVAILABLE", "自动侧车服务暂不可用"), true
	}
	if items {
		page, size, err := pagination(request)
		if err != nil {
			return applicationError(response, storagedomain.ErrValidation), true
		}
		value, err := application.Sidecars.Items(request.Context(), resource[1], page, size)
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	if statusRoute {
		value, err := application.Sidecars.Status(request.Context(), resource[1])
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	var body httprequest.Empty
	if !decodeBody(response, request, &body) {
		return 400, true
	}
	task, err := application.Sidecars.Request(request.Context(), resource[1], request.Header.Get("Idempotency-Key"))
	if err != nil {
		return applicationError(response, err), true
	}
	application.Jobs.Wake()
	response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
	writeJSON(response, 202, map[string]any{"data": task})
	return 202, true
}
