package sqlite

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"context"
	"database/sql"
)

// 生命周期即使失败仍持有持久范围锁，只有自己的重试可以继续；新任务不绕过锁。
func blockLifecycleTask(ctx context.Context, tx *sql.Tx, kind, comic, exclude string) error {
	var count int
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM directory_move_members WHERE comic_id=? AND task_id<>? AND detached=0", comic, exclude).Scan(&count); err != nil {
		return err
	}
	if count > 0 {
		return jobsdomain.ErrConflict
	}
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE comic_id=? AND id<>? AND type='lifecycle_audit' AND status IN('queued','running','cancelling')", comic, exclude).Scan(&count); err != nil {
		return err
	}
	if count > 0 {
		return jobsdomain.ErrConflict
	}
	if kind == "lifecycle_audit" {
		if exclude != "" {
			return blockAuditRetry(ctx, tx, comic, exclude)
		}
		return nil
	}
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM lifecycle_runs WHERE comic_id=? AND task_id<>? AND completed=0", comic, exclude).Scan(&count); err != nil {
		return err
	}
	if count > 0 {
		return jobsdomain.ErrConflict
	}
	return nil
}

// 重新执行只读核对仍需重新取得范围锁，不能绕过首次受理的并发限制。
func blockAuditRetry(ctx context.Context, tx *sql.Tx, comic, task string) error {
	queries := []struct {
		sql  string
		args []any
	}{
		{"SELECT COUNT(*) FROM tasks WHERE comic_id=? AND id<>? AND status IN('queued','running','cancelling')", []any{comic, task}},
		{"SELECT COUNT(*) FROM lifecycle_runs WHERE comic_id=? AND completed=0 AND task_id<>(SELECT owner_task_id FROM lifecycle_audits WHERE task_id=?)", []any{comic, task}},
		{"SELECT COUNT(*) FROM upload_sessions WHERE comic_id=? AND status IN('active','submitted','activated')", []any{comic}},
		{"SELECT COUNT(*) FROM metadata_refresh_runs WHERE comic_id=? AND applied=1 AND completed=0", []any{comic}},
		{"SELECT COUNT(*) FROM video_transcode_items i JOIN tasks t ON t.id=i.task_id WHERE t.comic_id=? AND i.status='prepared'", []any{comic}},
		{"SELECT COUNT(*) FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND m.hq_state='releasing'", []any{comic}},
	}
	for _, query := range queries {
		var count int
		if err := tx.QueryRowContext(ctx, query.sql, query.args...).Scan(&count); err != nil {
			return err
		}
		if count > 0 {
			return jobsdomain.ErrConflict
		}
	}
	return nil
}
