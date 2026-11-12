package sqlite

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
	"time"
)

func commandChildCapacity(ctx context.Context, query interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}) error {
	var count int
	if err := query.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks t JOIN batch_preview_items i ON i.child_task_id=t.id WHERE t.status IN('queued','running','cancelling')").Scan(&count); err != nil {
		return err
	}
	return maintenancedomain.ValidateCommandCapacity(0, count)
}
func (repo *repository) PendingCommand(ctx context.Context, task string, now time.Time) (maintenancedomain.Item, error) {
	if err := commandChildCapacity(ctx, repo.database.Reader); err != nil {
		return maintenancedomain.Item{}, err
	}
	query := "SELECT " + itemColumns + ` FROM batch_previews p JOIN batch_preview_counts state ON state.preview_id=p.id JOIN batch_preview_items i ON i.preview_id=p.id
 WHERE p.task_id=? AND i.status='pending' AND i.covered_by IS NULL AND i.next_try_at<=? AND NOT EXISTS(
 SELECT 1 FROM json_each(i.dependencies_json) dependency JOIN batch_preview_items ancestor ON ancestor.preview_id=i.preview_id AND ancestor.position=dependency.value WHERE ancestor.status IN('pending','dispatched'))
 AND ((i.entry_id='' AND i.target_stamp='') OR EXISTS(SELECT 1 FROM json_each(i.dependencies_json) d JOIN batch_preview_items a ON a.preview_id=i.preview_id AND a.position=d.value WHERE a.status IN('blocked','failed','cancelled'))
 OR NOT EXISTS(SELECT 1 FROM tasks busy JOIN batch_preview_items linked ON linked.child_task_id=busy.id WHERE busy.comic_id=i.comic_id AND busy.status IN('queued','running','cancelling'))) AND `
	item, err := scanItem(repo.database.Reader.QueryRowContext(ctx, query+"i.position>state.dispatch_cursor ORDER BY i.position LIMIT 1", task, now.Format(jobsdomain.TimestampFormat)))
	if !errors.Is(err, jobsdomain.ErrNotFound) {
		return item, err
	}
	return scanItem(repo.database.Reader.QueryRowContext(ctx, query+"i.position<=state.dispatch_cursor ORDER BY i.position LIMIT 1", task, now.Format(jobsdomain.TimestampFormat)))
}
func commandDependencies(ctx context.Context, reader interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}, preview string, dependencies []int) error {
	states := make([]string, 0, len(dependencies))
	for _, position := range dependencies {
		var state string
		err := reader.QueryRowContext(ctx, "SELECT status FROM batch_preview_items WHERE preview_id=? AND position=?", preview, position).Scan(&state)
		if errors.Is(err, sql.ErrNoRows) {
			return maintenancedomain.ErrDependencyFailed
		}
		if err != nil {
			return err
		}
		states = append(states, state)
	}
	return maintenancedomain.ValidateDependencyStates(states)
}
func (repo *repository) CommandPrerequisites(ctx context.Context, parent string, item maintenancedomain.Item) error {
	if len(item.Dependencies) == 0 && item.TargetStamp == "" {
		return nil
	}
	var preview string
	if err := repo.database.Reader.QueryRowContext(ctx, "SELECT id FROM batch_previews WHERE task_id=?", parent).Scan(&preview); err != nil {
		return err
	}
	if item.TargetStamp != "" {
		return trashDependency(ctx, repo.database.Reader, preview, item.ComicID)
	}
	return commandDependencies(ctx, repo.database.Reader, preview, item.Dependencies)
}
func (repo *repository) ActiveChildren(ctx context.Context, task string) ([]string, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, `SELECT i.child_task_id FROM batch_preview_items i JOIN batch_previews p ON p.id=i.preview_id JOIN tasks t ON t.id=i.child_task_id
 WHERE p.task_id=? AND t.status IN('queued','running','cancelling') ORDER BY i.position`, task)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
func (repo *repository) ChildAdmission(parent string, item maintenancedomain.Item, operation string) jobsports.Admission {
	return func(child string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			if err := commandChildCapacity(ctx, tx); err != nil {
				return err
			}
			var preview, childOperation, childEntry, childStamp string
			var revision int64
			err := tx.QueryRowContext(ctx, `SELECT p.id,COALESCE(s.revision,0),COALESCE(json_extract(child.request_json,'$.operation'),''),COALESCE(json_extract(child.request_json,'$.entryId'),''),COALESCE(json_extract(child.request_json,'$.confirmation'),'') FROM batch_previews p JOIN tasks t ON t.id=p.task_id JOIN batch_preview_items i ON i.preview_id=p.id
 LEFT JOIN active_comics c ON c.id=i.comic_id LEFT JOIN comic_sidecars s ON s.comic_id=c.id JOIN tasks child ON child.id=? AND child.comic_id=i.comic_id AND child.type=? AND child.target_type=i.target_type AND child.target_id=i.target_id
 WHERE p.task_id=? AND t.type='command_batch' AND t.target_type=? AND t.status='running' AND i.position=? AND i.comic_id=? AND i.entry_id=? AND i.entry_stamp=? AND i.target_stamp=? AND i.covered_by IS NULL AND i.status='pending' AND i.child_task_id IS NULL`, child, maintenancedomain.CommandChildType(operation), parent, operation, item.Position, item.ComicID, item.EntryID, item.EntryStamp, item.TargetStamp).Scan(&preview, &revision, &childOperation, &childEntry, &childStamp)
			if errors.Is(err, sql.ErrNoRows) {
				return jobsdomain.ErrConflict
			}
			if err != nil {
				return err
			}
			if maintenancedomain.UsesTargetSelection(operation) {
				if err = trashDependency(ctx, tx, preview, item.ComicID); err != nil {
					return err
				}
			} else if !maintenancedomain.UsesTrashSelection(operation) {
				if err = maintenancedomain.ValidateCommandRevision(revision, item.ScopeRevision); err != nil {
					return err
				}
			} else {
				if err = commandDependencies(ctx, tx, preview, item.Dependencies); err != nil {
					return err
				}
			}
			if err = maintenancedomain.ValidateChildEntry(operation, item, childEntry, childStamp); err != nil {
				return err
			}
			if err = maintenancedomain.ValidateChildOperation(operation, childOperation); err != nil {
				return err
			}
			if _, err = tx.ExecContext(ctx, "UPDATE batch_preview_items SET status='dispatched',child_task_id=?,wait_reason=NULL,next_try_at='' WHERE preview_id=? AND position=?", child, preview, item.Position); err != nil {
				return err
			}
			_, err = tx.ExecContext(ctx, "UPDATE batch_preview_counts SET dispatch_cursor=? WHERE preview_id=?", item.Position, preview)
			return err
		}
	}
}
func (repo *repository) DispatchResult(ctx context.Context, parent string, item maintenancedomain.Item, code string, final bool) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	update := maintenancedomain.DispatchUpdateFor(code, final, time.Now().UTC())
	if _, err = tx.ExecContext(ctx, `UPDATE batch_preview_items SET status=?,error_code=?,wait_reason=?,next_try_at=?
 WHERE preview_id=(SELECT p.id FROM batch_previews p JOIN tasks t ON t.id=p.task_id WHERE p.task_id=? AND t.status='running') AND position=? AND status='pending' AND covered_by IS NULL AND child_task_id IS NULL`, update.Status, update.ErrorCode, update.WaitReason, update.NextTryAt, parent, item.Position); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE batch_preview_counts SET dispatch_cursor=? WHERE preview_id=(SELECT id FROM batch_previews WHERE task_id=?)", item.Position, parent); err != nil {
		return err
	}
	return tx.Commit()
}
func (repo *repository) CommandRetry(task string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var count int
		if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks t JOIN batch_previews p ON p.task_id=t.id WHERE t.id=? AND t.type='command_batch'", task).Scan(&count); err != nil {
			return err
		}
		if count != 1 {
			return jobsdomain.ErrConflict
		}
		if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE type='command_batch' AND id<>? AND status IN('queued','running','cancelling')", task).Scan(&count); err != nil {
			return err
		}
		return maintenancedomain.ValidateCommandCapacity(count, 0)
	}
}
