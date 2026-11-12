package domain

import "errors"

type completionError struct {
	stage string
	cause error
}

func (failure *completionError) Error() string { return "任务完成失败：" + failure.stage }
func (failure *completionError) Unwrap() error { return failure.cause }

// 完成诊断仅使用固定阶段，不把底层异常文本送入日志或页面。
func CompletionError(stage string, cause error) error {
	switch stage {
	case "BEGIN", "STATUS", "STATE", "BUSINESS", "TASK", "COMMIT":
	default:
		stage = "UNKNOWN"
	}
	return &completionError{stage: stage, cause: cause}
}

func CompletionStage(err error) string {
	var failure *completionError
	if errors.As(err, &failure) {
		return failure.stage
	}
	return ""
}
