package httpapi

import (
	"net/http"

	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
)

func (application *Application) serveTaskQueries(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) == 0 || resource[0] != "tasks" || !(len(resource) == 1 || len(resource) == 2 && resource[1] == "summary") {
		return 0, false
	}
	if request.Method != http.MethodGet {
		response.Header().Set("Allow", "GET")
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	parameters := request.URL.Query()
	filter := jobsdomain.Filter{Type: parameters.Get("type"), Keyword: parameters.Get("keyword")}
	if len(resource) == 2 {
		if parameters.Has("status") {
			return fail(response, 400, "VALIDATION_ERROR", "汇总不接受状态筛选"), true
		}
		result, err := application.Jobs.Store.Summary(request.Context(), filter)
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, result)
		return 200, true
	}
	page, size, err := pagination(request)
	if err != nil {
		return fail(response, 400, "VALIDATION_ERROR", "分页参数无效"), true
	}
	oldest := parameters.Get("oldest")
	if oldest != "" && oldest != "true" && oldest != "false" {
		return fail(response, 400, "VALIDATION_ERROR", "oldest 参数无效"), true
	}
	result, err := application.Jobs.Store.List(request.Context(), jobsdomain.ListQuery{Filter: filter, Status: parameters.Get("status"), Page: page, PageSize: size, Oldest: oldest == "true"})
	if err != nil {
		return applicationError(response, err), true
	}
	writeData(response, result)
	return 200, true
}
