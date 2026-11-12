package sqlite

import (
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	"context"
	"database/sql"
)

const trashDependencySQL = `SELECT EXISTS(SELECT 1 FROM batch_preview_items i JOIN tasks t ON t.id=i.child_task_id
 JOIN lifecycle_runs r ON r.task_id=t.id WHERE i.preview_id=? AND i.comic_id=? AND r.comic_id=i.comic_id AND r.completed=0 AND t.status='failed')`

// 同批前项失败后仍持有漫画文件锁，剩余目标不能无限退避或越过检查点。
func trashDependency(ctx context.Context, reader interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}, preview, comic string) error {
	var failed bool
	err := reader.QueryRowContext(ctx, trashDependencySQL, preview, comic).Scan(&failed)
	if err != nil {
		return err
	}
	return maintenancedomain.ValidateTrashDependency(failed)
}
