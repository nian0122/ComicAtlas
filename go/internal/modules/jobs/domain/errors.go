package domain

import (
	"errors"
	"fmt"
)

var ErrNotFound = errors.New("任务不存在")

var ErrConflict = errors.New("任务状态或幂等键冲突")

var ErrTargetActive = fmt.Errorf("目标已有同类型活跃任务: %w", ErrConflict)

var ErrFull = errors.New("后台队列已满")

var ErrValidation = errors.New("任务参数无效")

var ErrStopped = errors.New("任务执行器已停止")
