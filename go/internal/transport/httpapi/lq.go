package httpapi

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"net/http"
)

func (application *Application) serveLQ(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	creation := len(resource) == 3 && (resource[0] == "comics" || resource[0] == "chapters") && resource[2] == "lq"
	items := len(resource) == 3 && resource[0] == "tasks" && resource[2] == "items"
	if !creation && !items {
		return 0, false
	}
	allowed := "POST"
	if items {
		allowed = "GET"
	}
	if request.Method != allowed {
		response.Header().Set("Allow", allowed)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if application.LQ == nil {
		return fail(response, 503, "UNAVAILABLE", "图片派生暂不可用"), true
	}
	if items {
		page, size, err := pagination(request)
		if err != nil {
			return fail(response, 400, "VALIDATION_ERROR", "分页参数无效"), true
		}
		task, err := application.Jobs.Store.Inspect(request.Context(), resource[1])
		if err != nil {
			return applicationError(response, err), true
		}
		if task.Type == "video_transcode" && application.Transcode != nil {
			value, err := application.Transcode.Items(request.Context(), resource[1], page, size)
			if err != nil {
				return applicationError(response, err), true
			}
			writeData(response, value)
			return 200, true
		}
		if task.Type == "metadata_refresh" && application.Metadata != nil {
			value, err := application.Metadata.Items(request.Context(), resource[1], page, size)
			if err != nil {
				return applicationError(response, err), true
			}
			writeData(response, value)
			return 200, true
		}
		if task.Type == "media_upload" && application.UploadPublishing != nil {
			value, err := application.UploadPublishing.Items(request.Context(), resource[1], page, size)
			if err != nil {
				return applicationError(response, err), true
			}
			writeData(response, value)
			return 200, true
		}
		if task.Type == "hq_release" && application.HQ != nil {
			value, err := application.HQ.Items(request.Context(), resource[1], page, size)
			if err != nil {
				return applicationError(response, err), true
			}
			writeData(response, value)
			return 200, true
		}
		value, err := application.LQ.Items(request.Context(), resource[1], page, size)
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	var body mediadomain.LQRequest
	if !decodeBody(response, request, &body) {
		return 400, true
	}
	kind := "comic"
	if resource[0] == "chapters" {
		kind = "chapter"
	}
	task, err := application.LQ.Submit(request.Context(), kind, resource[1], body, request.Header.Get("Idempotency-Key"))
	if err != nil {
		return applicationError(response, err), true
	}
	application.Jobs.Wake()
	response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
	writeJSON(response, 202, struct {
		Data jobsdomain.Task `json:"data"`
	}{Data: task})
	return 202, true
}
