package httpapi

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
	"net/http"
)

func (application *Application) serveHQ(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) != 3 || (resource[0] != "comics" && resource[0] != "chapters") || resource[2] != "hq-release" {
		return 0, false
	}
	if request.Method != "GET" && request.Method != "POST" {
		response.Header().Set("Allow", "GET, POST")
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if application.HQ == nil {
		return fail(response, 503, "UNAVAILABLE", "HQ 释放暂不可用"), true
	}
	kind := "comic"
	if resource[0] == "chapters" {
		kind = "chapter"
	}
	if request.Method == "GET" {
		preview, err := application.HQ.Preview(request.Context(), kind, resource[1])
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, preview)
		return 200, true
	}
	var body httprequest.Empty
	if !decodeBody(response, request, &body) {
		return 400, true
	}
	task, err := application.HQ.Submit(request.Context(), kind, resource[1], request.Header.Get("Idempotency-Key"))
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
