package domain

import "errors"

var (
	ErrConflict   = errors.New("存储操作与当前状态冲突")
	ErrNotFound   = errors.New("存储统计目标不存在")
	ErrValidation = errors.New("存储统计参数无效")
	ErrScanLimit  = errors.New("存储扫描超过限制")
	ErrScanRead   = errors.New("存储扫描读取失败")
)
