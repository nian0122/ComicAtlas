package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
)

func (repo *repository) GuardDirectoryRetry(id string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		if !exportdomain.TaskID(id) {
			return exportdomain.ErrValidation
		}
		var state exportdomain.DirectoryRetryState
		err := tx.QueryRowContext(ctx, `SELECT t.id,t.type,t.status,t.comic_id,t.target_type,t.target_id,COALESCE(t.request_json,''),
 COALESCE(r.preview_id,''),COALESCE(p.task_id,''),COALESCE(p.plan_sha,''),COALESCE(r.completed,1),
 (SELECT COUNT(*) FROM directory_move_members WHERE task_id=t.id),
 (SELECT COUNT(*) FROM directory_move_members WHERE task_id=t.id AND detached<>0)
 FROM tasks t LEFT JOIN directory_move_runs r ON r.task_id=t.id LEFT JOIN directory_move_previews p ON p.id=r.preview_id WHERE t.id=?`, id).Scan(&state.TaskID, &state.Type, &state.Status, &state.ComicID, &state.TargetType, &state.TargetID, &state.Payload, &state.PreviewID, &state.OwnerTaskID, &state.PlanSHA256, &state.Completed, &state.Members, &state.DetachedMembers)
		if errors.Is(err, sql.ErrNoRows) {
			return exportdomain.ErrNotFound
		}
		if err != nil {
			return err
		}
		return exportdomain.ValidateDirectoryRetry(id, state)
	}
}
