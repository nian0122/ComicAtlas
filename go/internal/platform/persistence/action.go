package persistence

import (
	"context"
	"database/sql"
)

// WriteAction 是同一数据库短事务内的提交动作；只由 SQLite 适配器创建和执行。
// 业务编排可转交该动作，但不能获取连接、事务或执行 SQL。
type WriteAction func(context.Context, *sql.Tx) error

// Sequence 组合短事务动作；任一步失败由外层事务整体回滚。
func Sequence(actions ...WriteAction) WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		for _, action := range actions {
			if action != nil {
				if err := action(ctx, tx); err != nil {
					return err
				}
			}
		}
		return nil
	}
}
