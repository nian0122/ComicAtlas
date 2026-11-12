package domain

import (
	"errors"
)

var ErrValidation = errors.New("阅读设置参数无效")

var ErrConflict = errors.New("阅读设置版本冲突")
