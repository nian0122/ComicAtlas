package httpapi

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"errors"
	"net/http"
)

func (application *Application) serveCoverFile(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) != 3 || !(resource[0] == "comics" && resource[2] == "cover") {
		return 0, false
	}
	if request.Method != "GET" && request.Method != "HEAD" {
		response.Header().Set("Allow", "GET, HEAD")
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if request.URL.RawQuery != "" {
		return fail(response, 400, "VALIDATION_ERROR", "封面不接受查询参数"), true
	}
	if application.Covers == nil {
		return fail(response, 503, "UNAVAILABLE", "封面暂不可用"), true
	}
	file, hash, err := application.Covers.Load(request.Context(), resource[1])
	if err != nil {
		return imageError(response, err), true
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return applicationError(response, err), true
	}
	response.Header().Set("Content-Type", "image/webp")
	response.Header().Set("Cache-Control", "private, no-cache")
	response.Header().Set("ETag", "\""+hash+"\"")
	tracked := &statusWriter{ResponseWriter: response, status: 200}
	http.ServeContent(tracked, request, "cover.webp", info.ModTime(), file)
	return tracked.status, true
}

func imageError(response http.ResponseWriter, err error) int {
	if errors.Is(err, lifecycledomain.ErrValidation) {
		return fail(response, 422, "COVER_SOURCE_INVALID", "封面来源无效")
	}
	if errors.Is(err, mediadomain.ErrImageUnavailable) {
		return fail(response, 503, "UNAVAILABLE", "图片工具暂不可用")
	}
	if errors.Is(err, mediadomain.ErrImageLimit) {
		return fail(response, 422, "COVER_RESOURCE_LIMIT", "图片超过解码资源限制")
	}
	if errors.Is(err, mediadomain.ErrImageInvalid) {
		return fail(response, 422, "COVER_SOURCE_INVALID", "图片内容无法用于封面")
	}
	if errors.Is(err, mediadomain.ErrImageChanged) || errors.Is(err, lifecycledomain.ErrChanged) || errors.Is(err, lifecycledomain.ErrJournal) {
		return fail(response, 409, "COVER_SOURCE_CHANGED", "源文件已变化，请刷新后重试")
	}
	return applicationError(response, err)
}
