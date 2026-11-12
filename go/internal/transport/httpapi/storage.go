package httpapi

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
	"net/http"
)

func (application *Application) serveStorage(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if status, handled := application.serveStorageBrowse(response, request, resource); handled {
		return status, true
	}
	kind, id := "global", ""
	refresh := len(resource) == 2 && resource[0] == "storage" && resource[1] == "refresh"
	global := len(resource) == 1 && resource[0] == "storage"
	scoped := len(resource) == 3 && (resource[0] == "comics" || resource[0] == "chapters") && resource[2] == "storage"
	if !refresh && !global && !scoped {
		return 0, false
	}
	allowed := "GET"
	if refresh {
		allowed = "POST"
	}
	if request.Method != allowed {
		response.Header().Set("Allow", allowed)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if application.Storage == nil {
		return fail(response, 503, "UNAVAILABLE", "存储统计暂不可用"), true
	}
	if refresh {
		var body httprequest.Empty
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		task, err := application.Storage.Refresh(request.Context(), request.Header.Get("Idempotency-Key"))
		if err != nil {
			return applicationError(response, err), true
		}
		application.Jobs.Wake()
		response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
		writeJSON(response, 202, struct {
			Data jobsdomain.Task `json:"data"`
		}{task})
		return 202, true
	}
	if scoped {
		kind, id = "comic", resource[1]
		if resource[0] == "chapters" {
			kind = "chapter"
		}
	}
	result, err := application.Storage.Statistics(request.Context(), kind, id)
	if err != nil {
		return applicationError(response, err), true
	}
	writeData(response, result)
	return 200, true
}
