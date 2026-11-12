package persistence

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"github.com/mattn/go-sqlite3"
)

// FailureClass 只返回固定类别和驱动数字码，不返回 SQL、文件路径或用户内容。
func FailureClass(err error) string {
	if errors.Is(err, context.Canceled) {
		return "CONTEXT_CANCELLED"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "CONTEXT_TIMEOUT"
	}
	if errors.Is(err, sql.ErrTxDone) {
		return "TX_CLOSED"
	}
	var failure sqlite3.Error
	if errors.As(err, &failure) {
		return fmt.Sprintf("SQLITE_%d_%d", failure.Code, failure.ExtendedCode)
	}
	return "OTHER"
}
