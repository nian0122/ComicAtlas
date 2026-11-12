package domain

import "errors"

var (
	ErrExists          = errors.New("恢复目标已有数据库记录")
	ErrConflict        = errors.New("恢复资料与数据库约束冲突")
	ErrValidation      = errors.New("恢复参数无效")
	ErrMetadata        = errors.New("恢复元数据无效")
	ErrMissing         = errors.New("恢复来源不存在")
	ErrUnsafe          = errors.New("恢复来源不是安全的普通目录或文件")
	ErrChanged         = errors.New("恢复来源在读取期间变化")
	ErrLimit           = errors.New("恢复来源超过预算")
	ErrContentMismatch = errors.New("恢复文件与元数据记录不符")
)
