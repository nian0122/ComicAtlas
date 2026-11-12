package domain

import "errors"

var ErrScopeChanged = errors.New("批量预览范围已经变化")
var ErrDependencyFailed = errors.New("批量依赖未成功")
