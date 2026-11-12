package domain

type failureError struct {
	code  string
	cause error
}

// NewFailure 保留原始 cause 供内部识别；Error 只提供目录内的固定摘要。
func NewFailure(code string, cause error) error {
	return &failureError{code: failureCode(code), cause: cause}
}
func (failure *failureError) Error() string {
	return failure.code + ": " + failures[failure.code].Message
}
func (failure *failureError) Unwrap() error { return failure.cause }
