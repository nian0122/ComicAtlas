package httpapi

import (
	"bytes"
	"net/http"
	"time"
)

func (application *Application) serveTrashCover(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) != 3 || resource[0] != "trash" || resource[2] != "cover" {
		return 0, false
	}
	if request.Method != "GET" && request.Method != "HEAD" {
		response.Header().Set("Allow", "GET, HEAD")
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if request.URL.RawQuery != "" {
		return fail(response, 400, "VALIDATION_ERROR", "回收封面不接受查询参数"), true
	}
	if application.TrashCovers == nil {
		return fail(response, 503, "UNAVAILABLE", "回收封面暂不可用"), true
	}
	cover, err := application.TrashCovers.Open(request.Context(), resource[1])
	if err != nil {
		return imageError(response, err), true
	}
	response.Header().Set("Content-Type", cover.ContentType)
	response.Header().Set("Cache-Control", "private, no-cache")
	response.Header().Set("ETag", "\""+cover.SHA256+"\"")
	tracked := &statusWriter{ResponseWriter: response, status: 200}
	http.ServeContent(tracked, request, "cover", time.Time{}, bytes.NewReader(cover.Data))
	return tracked.status, true
}
