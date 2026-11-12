package httpapi

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
	"net/http"
	"strings"
)

func (application *Application) serveUploads(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) == 0 || resource[0] != "upload-sessions" {
		return 0, false
	}
	allowed := ""
	switch {
	case len(resource) == 1:
		allowed = "POST"
	case len(resource) == 2:
		allowed = "GET, DELETE"
	case len(resource) == 4 && resource[2] == "files":
		allowed = "PUT"
	case len(resource) == 3 && resource[2] == "complete":
		allowed = "POST"
	default:
		return fail(response, 404, "NOT_FOUND", "资源不存在"), true
	}
	if !strings.Contains(", "+allowed+", ", ", "+request.Method+", ") {
		response.Header().Set("Allow", allowed)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if application.Uploads == nil {
		return fail(response, 503, "UNAVAILABLE", "上传会话暂不可用"), true
	}
	if len(resource) == 3 {
		if application.UploadPublishing == nil {
			return fail(response, 503, "UNAVAILABLE", "上传发布暂不可用"), true
		}
		var body httprequest.Empty
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		task, err := application.UploadPublishing.Submit(request.Context(), resource[1])
		if err != nil {
			return applicationError(response, err), true
		}
		application.Jobs.Wake()
		response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
		writeJSON(response, 202, map[string]any{"data": task})
		return 202, true
	}
	if len(resource) == 1 {
		var body mediadomain.UploadRequest
		if !decodeBodyLimit(response, request, &body, 8<<20) {
			return 400, true
		}
		value, err := application.Uploads.Create(request.Context(), body, request.Header.Get("Idempotency-Key"))
		if err != nil {
			return applicationError(response, err), true
		}
		response.Header().Set("Location", "/api/v1/upload-sessions/"+value.ID)
		writeJSON(response, 201, map[string]any{"data": value})
		return 201, true
	}
	if len(resource) == 2 {
		if request.Method == "GET" {
			value, err := application.Uploads.Status(request.Context(), resource[1])
			if err != nil {
				return applicationError(response, err), true
			}
			writeData(response, value)
			return 200, true
		}
		if err := application.Uploads.Cancel(request.Context(), resource[1]); err != nil {
			return applicationError(response, err), true
		}
		response.WriteHeader(204)
		return 204, true
	}
	if strings.Split(request.Header.Get("Content-Type"), ";")[0] != "application/octet-stream" {
		return fail(response, 400, "VALIDATION_ERROR", "分片需要 application/octet-stream"), true
	}
	if request.ContentLength > mediadomain.UploadChunkBytes {
		return fail(response, 413, "PAYLOAD_TOO_LARGE", "分片超过大小限制"), true
	}
	request.Body = http.MaxBytesReader(response, request.Body, mediadomain.UploadChunkBytes+1)
	value, err := application.Uploads.Receive(request.Context(), resource[1], resource[3], request.Header.Get("Content-Range"), request.Body)
	if err != nil {
		return applicationError(response, err), true
	}
	writeData(response, value)
	return 200, true
}
