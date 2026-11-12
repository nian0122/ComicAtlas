package domain

import "errors"

var (
	ErrScopeChanged = errors.New("回收条目恢复范围已改变")
	ErrConfirmation = errors.New("永久清理确认已失效或不匹配")
	ErrNotFound     = errors.New("生命周期对象不存在")
	ErrValidation   = errors.New("生命周期参数无效")
	ErrConflict     = errors.New("生命周期文件状态冲突")
	ErrMissing      = errors.New("已登记文件丢失")
	ErrChanged      = errors.New("生命周期文件已改变")
	ErrJournal      = errors.New("生命周期清单无效")
	// ErrPurged 表示对象已永久清理，文件清单不再保留，不能继续核对或修复。
	ErrPurged = errors.New("对象已永久清理，文件清单已删除")
	// ErrRestored 表示对象已还原，回收文件清单已清理，不能继续核对或修复。
	ErrRestored = errors.New("对象已还原，回收文件清单已清理")
)
