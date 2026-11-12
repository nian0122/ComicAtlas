package domain

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"errors"
	"time"
)

func ValidateOutcome(value Outcome, hasWrite bool) error {
	if !Key(value.TaskID) || !Key(value.ComicID) || value.TaskAttempt < 1 || value.TryCount != 0 || len(value.UpdatedAt) > 40 {
		return ErrValidation
	}
	if _, err := time.Parse(time.RFC3339Nano, value.UpdatedAt); err != nil {
		return ErrValidation
	}
	if value.SourceSHA256 != nil && !validHash(*value.SourceSHA256) {
		return ErrValidation
	}
	switch value.Status {
	case ResultRestored:
		if !hasWrite || value.SourceSHA256 == nil || value.ErrorCode != nil {
			return ErrValidation
		}
	case ResultPlaceholder:
		if !hasWrite || value.SourceSHA256 != nil || value.ErrorCode != nil {
			return ErrValidation
		}
	case ResultSkipped, ResultEmpty:
		if hasWrite || value.ErrorCode != nil {
			return ErrValidation
		}
	case ResultError:
		if hasWrite || value.ErrorCode == nil {
			return ErrValidation
		}
		switch *value.ErrorCode {
		case CodeConflict, CodeMetadata, CodeMissing, CodeChanged, CodeMismatch, CodeUnavailable, CodeUnsafe, CodeLimit:
		default:
			return ErrValidation
		}
	default:
		return ErrValidation
	}
	return nil
}

func ShouldCommitOutcome(run DiscoveryRun, previous *Outcome, value Outcome) (bool, error) {
	if err := ValidateDiscoveryWrite(run); err != nil {
		return false, err
	}
	if !run.Sealed || run.TaskID != value.TaskID || run.Attempt != value.TaskAttempt {
		return false, ErrConflict
	}
	if previous == nil {
		return true, nil
	}
	if previous.TaskAttempt > run.Attempt {
		return false, ErrConflict
	}
	if previous.Status != ResultError || previous.TaskAttempt == run.Attempt {
		return false, nil
	}
	if previous.TryCount >= MaxResultTries {
		return false, ErrLimit
	}
	return true, nil
}

// WriteFailure 只转换明确的逐本冲突；取消及存储系统错误由调用方整体回滚。
func WriteFailure(err error) (status string, code *string, handled bool) {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return "", nil, false
	}
	if errors.Is(err, ErrExists) {
		return ResultSkipped, nil, true
	}
	value := ""
	switch {
	case errors.Is(err, ErrConflict):
		value = CodeConflict
	case errors.Is(err, ErrMetadata):
		value = CodeMetadata
	case errors.Is(err, ErrMissing):
		value = CodeMissing
	case errors.Is(err, ErrChanged):
		value = CodeChanged
	case errors.Is(err, ErrContentMismatch):
		value = CodeMismatch
	case errors.Is(err, ErrUnsafe):
		value = CodeUnsafe
	case errors.Is(err, ErrLimit):
		value = CodeLimit
	case errors.Is(err, mediadomain.ErrUnavailable):
		value = CodeUnavailable
	default:
		return "", nil, false
	}
	return ResultError, &value, true
}
