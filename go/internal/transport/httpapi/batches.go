package httpapi

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	"net/http"
)

func (application *Application) serveBatches(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	create := len(resource) == 1 && resource[0] == "batch-previews"
	detail := len(resource) == 2 && resource[0] == "batch-previews"
	items := len(resource) == 3 && resource[0] == "batch-previews" && resource[2] == "items"
	confirm := len(resource) == 3 && resource[0] == "batch-previews" && resource[2] == "confirm"
	taskItems := len(resource) == 3 && resource[0] == "tasks" && resource[2] == "items"
	if !create && !detail && !items && !confirm && !taskItems {
		return 0, false
	}
	id := ""
	if len(resource) > 1 {
		id = resource[1]
	}
	if taskItems {
		if application.Jobs == nil {
			return 0, false
		}
		task, err := application.Jobs.Store.Inspect(request.Context(), id)
		if err != nil {
			return applicationError(response, err), true
		}
		if task.Type != "metadata_batch" && task.Type != "command_batch" {
			return 0, false
		}
		id = task.TargetID
	}
	method := "GET"
	if create || confirm {
		method = "POST"
	}
	if request.Method != method {
		response.Header().Set("Allow", method)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if application.Maintenance == nil {
		return fail(response, 503, "UNAVAILABLE", "批量维护暂不可用"), true
	}
	ctx := request.Context()
	if create {
		var body maintenancedomain.Request
		if !decodeBodyLimit(response, request, &body, 1<<20) {
			return 400, true
		}
		value, err := application.Maintenance.Preview(ctx, body)
		if err != nil {
			return applicationError(response, err), true
		}
		response.Header().Set("Location", "/api/v1/batch-previews/"+value.ID)
		writeJSON(response, 201, struct {
			Data maintenancedomain.Preview `json:"data"`
		}{value})
		return 201, true
	}
	if confirm {
		var body maintenancedomain.Confirmation
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		task, err := application.Maintenance.Confirm(ctx, id, request.Header.Get("Idempotency-Key"), body)
		if err != nil {
			return applicationError(response, err), true
		}
		application.Jobs.Wake()
		response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
		writeJSON(response, 202, DataResponse[jobsdomain.Task]{Data: task})
		return 202, true
	}
	if items || taskItems {
		page, size, err := pagination(request)
		if err != nil {
			return applicationError(response, err), true
		}
		value, err := application.Maintenance.Items(ctx, id, page, size)
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	value, err := application.Maintenance.Get(ctx, id)
	if err != nil {
		return applicationError(response, err), true
	}
	writeData(response, value)
	return 200, true
}
