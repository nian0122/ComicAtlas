package domain

import (
	"errors"
)

var ErrNotFound = errors.New("内容不存在")

var ErrInvalidProgress = errors.New("阅读位置无效")

var ErrValidation = errors.New("资料参数无效")

var ErrConflict = errors.New("资料版本、名称或引用冲突")
