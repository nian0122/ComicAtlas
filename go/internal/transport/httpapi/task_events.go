package httpapi

import (
	"net/http"
	"strconv"

	"comicatlas.local/backend/internal/modules/jobs/domain"
)

func (application *Application) serveTaskEvents(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) != 3 || resource[0] != "tasks" || resource[2] != "events" {
		return 0, false
	}
	if request.Method != http.MethodGet {
		response.Header().Set("Allow", "GET")
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	values := request.URL.Query()
	for key, entries := range values {
		if (key != "after" && key != "limit") || len(entries) != 1 {
			return fail(response, 400, "VALIDATION_ERROR", "事件分页参数无效"), true
		}
	}
	after, limit := int64(0), 50
	var err error
	if raw := values.Get("after"); raw != "" {
		after, err = strconv.ParseInt(raw, 10, 64)
		if err != nil || after < 0 {
			return fail(response, 400, "VALIDATION_ERROR", "事件游标无效"), true
		}
	}
	if raw := values.Get("limit"); raw != "" {
		limit, err = strconv.Atoi(raw)
		if err != nil || limit < 1 || limit > 100 {
			return fail(response, 400, "VALIDATION_ERROR", "事件数量无效"), true
		}
	}
	if application.Jobs == nil {
		return fail(response, 503, "UNAVAILABLE", "任务服务暂不可用"), true
	}
	page, err := application.Jobs.Store.Events(request.Context(), resource[1], after, limit)
	if err != nil {
		if err == domain.ErrValidation {
			return fail(response, 400, "VALIDATION_ERROR", "事件分页参数无效"), true
		}
		return applicationError(response, err), true
	}
	writeData(response, page)
	return 200, true
}
