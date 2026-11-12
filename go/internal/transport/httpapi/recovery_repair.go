package httpapi

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"net/http"
)

func (application *Application) serveRecoveryRepair(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	create := len(resource) == 4 && resource[0] == "comics" && resource[2] == "recovery-placeholder" && resource[3] == "repair"
	result := len(resource) == 3 && resource[0] == "tasks" && resource[2] == "recovery-repair"
	if !create && !result {
		return 0, false
	}
	method := http.MethodGet
	if create {
		method = http.MethodPost
	}
	if request.Method != method {
		response.Header().Set("Allow", method)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if request.URL.RawQuery != "" || request.URL.ForceQuery {
		return fail(response, 400, "VALIDATION_ERROR", "补全接口不接受查询参数"), true
	}
	if application.RecoveryRepair == nil || application.Jobs == nil {
		return fail(response, 503, "UNAVAILABLE", "占位补全暂不可用"), true
	}
	if result {
		value, err := application.RecoveryRepair.Result(request.Context(), resource[1])
		if err != nil {
			return recoveryError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	var body recoverydomain.RepairRequest
	if !decodeBody(response, request, &body) {
		return 400, true
	}
	task, err := application.RecoveryRepair.Submit(request.Context(), resource[1], body, request.Header.Get("Idempotency-Key"))
	if err != nil {
		return recoveryError(response, err), true
	}
	application.Jobs.Wake()
	response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
	writeJSON(response, 202, DataResponse[jobsdomain.Task]{Data: task})
	return 202, true
}
