package httpapi

import (
	"net/http"

	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
)

func (application *Application) serveMetadataRefresh(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) != 3 || resource[0] != "comics" || resource[2] != "refresh" {
		return 0, false
	}
	if request.Method != "POST" {
		response.Header().Set("Allow", "POST")
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if application.Metadata == nil {
		return fail(response, 503, "UNAVAILABLE", "元数据刷新暂不可用"), true
	}
	var body httprequest.Empty
	if !decodeBody(response, request, &body) {
		return 400, true
	}
	task, err := application.Metadata.Submit(request.Context(), resource[1], request.Header.Get("Idempotency-Key"))
	if err != nil {
		return applicationError(response, err), true
	}
	application.Jobs.Wake()
	response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
	writeJSON(response, 202, map[string]any{"data": task})
	return 202, true
}
