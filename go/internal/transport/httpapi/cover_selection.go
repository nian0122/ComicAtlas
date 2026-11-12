package httpapi

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
	"net/http"
)

func (application *Application) serveCoverSelection(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) != 3 || resource[0] != "comics" || resource[2] != "cover-selection" {
		return 0, false
	}
	if request.Method != "GET" && request.Method != "PUT" {
		response.Header().Set("Allow", "GET, PUT")
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if request.URL.RawQuery != "" {
		return fail(response, 400, "VALIDATION_ERROR", "封面选择不接受查询参数"), true
	}
	if application.Covers == nil {
		return fail(response, 503, "UNAVAILABLE", "封面服务暂不可用"), true
	}
	if request.Method == "GET" {
		selection, err := application.Covers.CoverSelection(request.Context(), resource[1])
		if err != nil {
			return applicationError(response, err), true
		}
		writeJSON(response, 200, DataResponse[mediadomain.CoverSelection]{Data: selection})
		return 200, true
	}
	var body httprequest.CoverSelection
	if !decodeBody(response, request, &body) {
		return 400, true
	}
	selection, err := application.Covers.SelectCover(request.Context(), resource[1], body.Version, body.MediaID)
	if err != nil {
		return applicationError(response, err), true
	}
	writeJSON(response, 200, DataResponse[mediadomain.CoverSelection]{Data: selection})
	return 200, true
}
