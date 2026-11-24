package domain

import (
	"errors"
)

var ErrNotFound = errors.New("内容不存在")

var ErrInvalidProgress = errors.New("阅读位置无效")

var ErrValidation = errors.New("资料参数无效")

var ErrConflict = errors.New("资料版本、名称或引用冲突")

// ErrReferenced 表示词条仍被作品引用；与版本冲突分开，便于返回可操作的提示。
var ErrReferenced = errors.New("词条仍被作品引用")
