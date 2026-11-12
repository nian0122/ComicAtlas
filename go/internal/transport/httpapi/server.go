// Package httpapi 实现统一 JSON 响应与运行诊断。
package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"
)

type Readiness interface{ Ready(context.Context) error }

func New(database Readiness, logger *slog.Logger, application *Application) http.Handler {
	return newHandler(database, logger, application, false)
}

func NewReading(database Readiness, logger *slog.Logger, application *Application) http.Handler {
	return newHandler(database, logger, application, true)
}

func newHandler(database Readiness, logger *slog.Logger, application *Application, reading bool) http.Handler {
	access := "management"
	if reading {
		access = "reading"
	}
	return http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		started := time.Now()
		var identifier [16]byte
		if _, err := rand.Read(identifier[:]); err != nil {
			writeError(response, http.StatusInternalServerError, "INTERNAL_ERROR", "请求标识生成失败")
			return
		}
		requestID := hex.EncodeToString(identifier[:])
		response.Header().Set("X-Request-ID", requestID)
		response.Header().Set("X-Content-Type-Options", "nosniff")
		status := http.StatusOK
		defer func() {
			if recover() != nil {
				status = http.StatusInternalServerError
				writeError(response, status, "INTERNAL_ERROR", "服务内部错误")
			}
			// 不记录查询参数、请求体或客户端原始请求头。
			logger.Info("HTTP 请求完成", "requestId", requestID, "access", access, "method", request.Method,
				"status", status, "durationMs", time.Since(started).Milliseconds())
		}()
		if reading && !readingAllowed(request) {
			status = http.StatusForbidden
			writeError(response, status, "READING_ACCESS_DENIED", "阅读入口不允许此操作")
			return
		}
		known := request.URL.Path == "/health/live" || request.URL.Path == "/health/ready" || request.URL.Path == "/api/v1/system"
		if !known {
			if application != nil {
				status = application.Serve(response, request)
				return
			}
			status = http.StatusNotFound
			writeError(response, status, "NOT_FOUND", "资源不存在")
			return
		}
		if request.Method != http.MethodGet {
			status = http.StatusMethodNotAllowed
			response.Header().Set("Allow", http.MethodGet)
			writeError(response, status, "METHOD_NOT_ALLOWED", "请求方法不受支持")
			return
		}
		switch request.URL.Path {
		case "/health/ready":
			ctx, cancel := context.WithTimeout(request.Context(), time.Second)
			defer cancel()
			if err := database.Ready(ctx); err != nil {
				status = http.StatusServiceUnavailable
				logger.Warn("数据库就绪检查失败", "requestId", requestID)
				writeError(response, status, "NOT_READY", "服务尚未就绪")
				return
			}
			writeData(response, StatusResponse{Status: "ready"})
		case "/health/live":
			writeData(response, StatusResponse{Status: "up"})
		case "/api/v1/system":
			writeData(response, SystemResponse{Name: "ComicAtlas-go", APIVersion: "v1"})
		}
	})
}

func writeData(response http.ResponseWriter, value any) {
	writeJSON(response, http.StatusOK, DataResponse[any]{Data: value})
}

func writeError(response http.ResponseWriter, status int, code, message string) {
	writeJSON(response, status, ErrorResponse{Error: APIError{code, message, []string{}}})
}

func writeJSON(response http.ResponseWriter, status int, value any) {
	response.Header().Set("Content-Type", "application/json; charset=utf-8")
	response.Header().Set("Cache-Control", "no-store")
	response.WriteHeader(status)
	_ = json.NewEncoder(response).Encode(value)
}
