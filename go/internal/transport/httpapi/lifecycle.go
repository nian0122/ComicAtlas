package httpapi

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
	"net/http"
)

func (application *Application) serveLifecycle(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	trash := len(resource) == 3 && resource[2] == "trash" && (resource[0] == "comics" || resource[0] == "chapters" || resource[0] == "media")
	entry := resource[0] == "trash" && (len(resource) == 1 || len(resource) == 2 || len(resource) == 3 && (resource[2] == "restore" || resource[2] == "purge-preview" || resource[2] == "purge"))
	if !trash && !entry {
		return 0, false
	}
	method := "GET"
	if trash || len(resource) == 3 {
		method = "POST"
	}
	if request.Method != method {
		response.Header().Set("Allow", method)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if application.Lifecycle == nil {
		return fail(response, 503, "UNAVAILABLE", "生命周期服务暂不可用"), true
	}
	ctx := request.Context()
	if len(resource) == 1 {
		page, size, err := pagination(request)
		if err != nil {
			return applicationError(response, lifecycledomain.ErrValidation), true
		}
		state := request.URL.Query().Get("state")
		if state == "" {
			state = "trashed"
		}
		value, err := application.Lifecycle.Entries(ctx, lifecycledomain.EntryQuery{Page: page, PageSize: size, State: state, Keyword: request.URL.Query().Get("keyword")})
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	if len(resource) == 2 {
		value, err := application.Lifecycle.Entry(ctx, resource[1])
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	if len(resource) == 3 && resource[0] == "trash" && resource[2] == "purge-preview" {
		var body httprequest.Empty
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		value, err := application.Lifecycle.PreviewPurge(ctx, resource[1])
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	var task jobsdomain.Task
	var err error
	key := request.Header.Get("Idempotency-Key")
	if len(resource) == 3 && resource[0] == "trash" && resource[2] == "purge" {
		var body httprequest.Purge
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		task, err = application.Lifecycle.Purge(ctx, resource[1], body.Token, key)
	} else {
		var body httprequest.Empty
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		if trash {
			kind := map[string]string{"comics": "comic", "chapters": "chapter", "media": "media"}[resource[0]]
			task, err = application.Lifecycle.Trash(ctx, kind, resource[1], key)
		} else {
			task, err = application.Lifecycle.Restore(ctx, resource[1], key)
		}
	}
	if err != nil {
		return applicationError(response, err), true
	}
	application.Jobs.Wake()
	response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
	writeJSON(response, 202, map[string]any{"data": task})
	return 202, true
}
