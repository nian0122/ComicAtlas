package httpapi

import (
	"context"
	"net/http"
	"time"
)

func (application *Application) serveToolCapabilities(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) != 2 || resource[0] != "system" || resource[1] != "capabilities" {
		return 0, false
	}
	if request.Method != http.MethodGet {
		response.Header().Set("Allow", http.MethodGet)
		return fail(response, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if request.URL.RawQuery != "" || request.URL.ForceQuery {
		return fail(response, http.StatusBadRequest, "VALIDATION_ERROR", "不接受查询参数"), true
	}
	if application.ToolCapabilities == nil {
		return fail(response, http.StatusServiceUnavailable, "UNAVAILABLE", "媒体工具能力查询暂不可用"), true
	}
	ctx, cancel := context.WithTimeout(request.Context(), 13*time.Second)
	defer cancel()
	value, err := application.ToolCapabilities.Read(ctx)
	if err != nil {
		return fail(response, http.StatusServiceUnavailable, "UNAVAILABLE", "媒体工具能力查询暂不可用"), true
	}
	writeData(response, value)
	return http.StatusOK, true
}
