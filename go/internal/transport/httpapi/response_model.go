package httpapi

// DataResponse 是管理与阅读 API 共用的 JSON 成功信封。
type DataResponse[T any] struct {
	Data T `json:"data"`
}

type APIError struct {
	Code    string   `json:"code"`
	Message string   `json:"message"`
	Details []string `json:"details"`
}

type ErrorResponse struct {
	Error APIError `json:"error"`
}

type StatusResponse struct {
	Status string `json:"status"`
}

type SystemResponse struct {
	Name       string `json:"name"`
	APIVersion string `json:"apiVersion"`
}
