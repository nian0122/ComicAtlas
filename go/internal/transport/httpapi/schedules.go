package httpapi

import (
	"errors"
	"net/http"
	"strconv"
	"time"

	schedulerdomain "comicatlas.local/backend/internal/modules/scheduler/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
)

type scheduleUpdateRequest struct {
	Enabled         *bool `json:"enabled"`
	IntervalSeconds *int  `json:"intervalSeconds"`
}

// serveSchedules 提供所有代码注册定时器的管理：列表、详情、日志、手动执行、启用与间隔调整。
func (application *Application) serveSchedules(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) < 1 || resource[0] != "schedules" {
		return 0, false
	}
	if application.Scheduler == nil {
		return fail(response, http.StatusServiceUnavailable, "UNAVAILABLE", "定时器暂不可用"), true
	}
	if len(resource) == 1 {
		switch request.Method {
		case http.MethodGet:
			limit, ok := scheduleLimit(request, "runs", 5, 50)
			if !ok {
				return fail(response, http.StatusBadRequest, "VALIDATION_ERROR", "查询参数无效"), true
			}
			items, err := application.Scheduler.List(request.Context(), limit)
			if err != nil {
				return applicationError(response, err), true
			}
			writeData(response, items)
			return http.StatusOK, true
		default:
			response.Header().Set("Allow", "GET")
			return fail(response, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
		}
	}
	id := resource[1]
	if len(resource) == 3 && resource[2] == "run" {
		if request.Method != http.MethodPost {
			response.Header().Set("Allow", "POST")
			return fail(response, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
		}
		var body httprequest.Empty
		if !decodeBody(response, request, &body) {
			return http.StatusBadRequest, true
		}
		run, err := application.Scheduler.Trigger(id)
		if errors.Is(err, schedulerdomain.ErrNotFound) {
			return fail(response, http.StatusNotFound, "NOT_FOUND", "定时器不存在"), true
		}
		if errors.Is(err, schedulerdomain.ErrConflict) {
			return fail(response, http.StatusConflict, "CONFLICT", "定时器正在执行"), true
		}
		if errors.Is(err, schedulerdomain.ErrUnavailable) {
			return fail(response, http.StatusServiceUnavailable, "UNAVAILABLE", "定时器暂不可用"), true
		}
		if err != nil {
			return applicationError(response, err), true
		}
		writeJSON(response, http.StatusAccepted, struct {
			Data schedulerdomain.Run `json:"data"`
		}{run})
		return http.StatusAccepted, true
	}
	if len(resource) == 3 && resource[2] == "runs" {
		if request.Method != http.MethodGet {
			response.Header().Set("Allow", "GET")
			return fail(response, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
		}
		limit, ok := scheduleLimit(request, "limit", 20, 100)
		if !ok {
			return fail(response, http.StatusBadRequest, "VALIDATION_ERROR", "查询参数无效"), true
		}
		schedule, err := application.Scheduler.Get(request.Context(), id, limit)
		if errors.Is(err, schedulerdomain.ErrNotFound) {
			return fail(response, http.StatusNotFound, "NOT_FOUND", "定时器不存在"), true
		}
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, schedule.Runs)
		return http.StatusOK, true
	}
	if len(resource) != 2 {
		return fail(response, http.StatusNotFound, "NOT_FOUND", "资源不存在"), true
	}
	switch request.Method {
	case http.MethodGet:
		limit, ok := scheduleLimit(request, "runs", 20, 100)
		if !ok {
			return fail(response, http.StatusBadRequest, "VALIDATION_ERROR", "查询参数无效"), true
		}
		schedule, err := application.Scheduler.Get(request.Context(), id, limit)
		if errors.Is(err, schedulerdomain.ErrNotFound) {
			return fail(response, http.StatusNotFound, "NOT_FOUND", "定时器不存在"), true
		}
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, schedule)
		return http.StatusOK, true
	case http.MethodPatch:
		if len(request.URL.Query()) != 0 {
			return fail(response, http.StatusBadRequest, "VALIDATION_ERROR", "查询参数无效"), true
		}
		var body scheduleUpdateRequest
		if !decodeBody(response, request, &body) {
			return http.StatusBadRequest, true
		}
		if body.Enabled == nil && body.IntervalSeconds == nil {
			return fail(response, http.StatusBadRequest, "VALIDATION_ERROR", "至少提供启用状态或间隔"), true
		}
		update := schedulerdomain.Update{Enabled: body.Enabled}
		if body.IntervalSeconds != nil {
			if *body.IntervalSeconds < int(schedulerdomain.MinInterval/time.Second) || *body.IntervalSeconds > int(schedulerdomain.MaxInterval/time.Second) {
				return fail(response, http.StatusBadRequest, "VALIDATION_ERROR", "间隔必须在 1 分钟到 7 天之间"), true
			}
			interval := time.Duration(*body.IntervalSeconds) * time.Second
			update.Interval = &interval
		}
		schedule, err := application.Scheduler.Update(request.Context(), id, update)
		if errors.Is(err, schedulerdomain.ErrNotFound) {
			return fail(response, http.StatusNotFound, "NOT_FOUND", "定时器不存在"), true
		}
		if errors.Is(err, schedulerdomain.ErrValidation) {
			return fail(response, http.StatusBadRequest, "VALIDATION_ERROR", "间隔必须在 1 分钟到 7 天之间"), true
		}
		if errors.Is(err, schedulerdomain.ErrUnavailable) {
			return fail(response, http.StatusServiceUnavailable, "UNAVAILABLE", "定时器暂不可用"), true
		}
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, schedule)
		return http.StatusOK, true
	default:
		response.Header().Set("Allow", "GET, PATCH")
		return fail(response, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
}

// scheduleLimit 只允许一个已知的 limit 参数，避免把客户端拼写错误当成默认值。
func scheduleLimit(request *http.Request, key string, fallback, maximum int) (int, bool) {
	for name, values := range request.URL.Query() {
		if name != key || len(values) != 1 {
			return 0, false
		}
		parsed, err := strconv.Atoi(values[0])
		if err != nil || parsed < 1 || parsed > maximum {
			return 0, false
		}
		fallback = parsed
	}
	return fallback, true
}
