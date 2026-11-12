package httpapi

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
	"net/http"
)

func (application *Application) serveLifecycleAudit(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	entry := len(resource) == 3 && resource[0] == "trash" && (resource[2] == "reconcile" || resource[2] == "repair")
	items := len(resource) == 3 && resource[0] == "tasks" && resource[2] == "items"
	if !entry && !items {
		return 0, false
	}
	if items {
		if application.Lifecycle == nil || application.Jobs == nil {
			return 0, false
		}
		task, err := application.Jobs.Store.Inspect(request.Context(), resource[1])
		if err != nil {
			return applicationError(response, err), true
		}
		if task.Type != "lifecycle_audit" && task.Type != "lifecycle" {
			return 0, false
		}
		if request.Method != "GET" {
			response.Header().Set("Allow", "GET")
			return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
		}
		page, size, err := pagination(request)
		if err != nil {
			return applicationError(response, lifecycledomain.ErrValidation), true
		}
		if task.Type == "lifecycle" {
			value, err := application.Lifecycle.FileResults(request.Context(), resource[1], page, size)
			if err != nil {
				return applicationError(response, err), true
			}
			writeData(response, value)
			return 200, true
		}
		value, err := application.Lifecycle.AuditItems(request.Context(), resource[1], page, size)
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	allow := "POST"
	if resource[2] == "reconcile" {
		allow = "GET, POST"
	}
	if request.Method != "POST" && !(request.Method == "GET" && resource[2] == "reconcile") {
		response.Header().Set("Allow", allow)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if application.Lifecycle == nil {
		return fail(response, 503, "UNAVAILABLE", "生命周期服务暂不可用"), true
	}
	if request.Method == "GET" {
		value, err := application.Lifecycle.AuditReport(request.Context(), resource[1])
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
	var task jobsdomain.Task
	var err error
	if resource[2] == "reconcile" {
		task, err = application.Lifecycle.Reconcile(request.Context(), resource[1], request.Header.Get("Idempotency-Key"))
	} else {
		task, err = application.Lifecycle.Repair(request.Context(), resource[1])
	}
	if err != nil {
		return applicationError(response, err), true
	}
	application.Jobs.Wake()
	response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
	writeJSON(response, 202, map[string]any{"data": task})
	return 202, true
}
