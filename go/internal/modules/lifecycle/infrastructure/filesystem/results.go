package filesystem

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"errors"
	"os"
	"time"
)

func reportFile(visit func(lifecycledomain.FileResult) error, id, operation, phase string, position int, item lifecycledomain.RetainedFile, operationErr error) error {
	if visit == nil || errors.Is(operationErr, context.Canceled) || errors.Is(operationErr, context.DeadlineExceeded) {
		return operationErr
	}
	result := lifecycledomain.FileResult{EntryID: id, Operation: operation, Phase: phase, Position: position, State: "applied", ObservedAt: time.Now().UTC().Format(time.RFC3339Nano)}
	result.Role, result.FileName = lifecycledomain.FileLabel(item.Source.Path)
	if item.Missing {
		result.State = "initially_missing"
	}
	if operationErr != nil {
		result.State = "failed"
		switch {
		case errors.Is(operationErr, lifecycledomain.ErrChanged):
			result.ErrorCode = "FILE_CHANGED"
		case errors.Is(operationErr, lifecycledomain.ErrMissing), errors.Is(operationErr, os.ErrNotExist):
			result.ErrorCode = "FILE_MISSING"
		case errors.Is(operationErr, lifecycledomain.ErrConflict):
			result.ErrorCode = "FILE_LOCATION_CONFLICT"
		case errors.Is(operationErr, os.ErrPermission):
			result.ErrorCode = "FILE_ACCESS_DENIED"
		default:
			result.ErrorCode = "FILE_IO_FAILED"
		}
	}
	return errors.Join(operationErr, visit(result))
}
