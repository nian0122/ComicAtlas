package domain

import "errors"

var (
	ErrValidation = errors.New("备份参数或清单无效")
	ErrInvalid    = errors.New("备份内容、格式或校验不一致")
	ErrExists     = errors.New("备份或恢复目标已存在")
	ErrSource     = errors.New("备份来源缺失、变化或包含不支持的文件")
	ErrBusy       = errors.New("应用仍在运行，请停止写入后备份")
	ErrIO         = errors.New("备份文件操作未完成")
	ErrNotFound   = errors.New("备份清单条目不存在")
	ErrPublished  = errors.New("结果已发布，但目录同步未确认，请校验目标后再使用")
)
