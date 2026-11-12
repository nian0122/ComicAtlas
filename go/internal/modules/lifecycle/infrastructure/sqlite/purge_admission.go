package sqlite

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
	"time"
)

func (repo *repository) ConfirmPurge(ctx context.Context, expected lifecycledomain.PurgeScope, days int, tokenHash, expires string) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	current, err := purgeScope(ctx, tx, expected.Root.ID)
	if err != nil {
		return err
	}
	if err = blockChanges(ctx, tx, current.Root.Target.ComicID, ""); err != nil {
		return err
	}
	now := time.Now().UTC()
	preview, err := lifecycledomain.EvaluatePurge(current, days, now)
	if err != nil {
		return err
	}
	before, err := lifecycledomain.EvaluatePurge(expected, days, now)
	if err != nil {
		return err
	}
	if !preview.Allowed || preview.Stamp != before.Stamp {
		return lifecycledomain.ErrConfirmation
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM lifecycle_confirmations WHERE expires_at<=? OR consumed_by IS NOT NULL", now.Format(jobsdomain.TimestampFormat)); err != nil {
		return err
	}
	var count int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM lifecycle_confirmations").Scan(&count); err != nil {
		return err
	}
	if count >= lifecycledomain.MaxConfirmations {
		return jobsdomain.ErrFull
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO lifecycle_confirmations(token_hash,entry_id,scope_hash,retention_days,expires_at) VALUES(?,?,?,?,?)", tokenHash, current.Root.ID, preview.Stamp, days, expires); err != nil {
		return err
	}
	return tx.Commit()
}

func (repo *repository) PurgeAdmission(request lifecycledomain.Request, days int) jobsports.Admission {
	return func(task string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			if request.Operation != "purge" || lifecycledomain.ValidateRequest(request) != nil {
				return lifecycledomain.ErrValidation
			}
			scope, err := purgeScope(ctx, tx, request.EntryID)
			if err != nil {
				return err
			}
			if err = blockChanges(ctx, tx, scope.Root.Target.ComicID, task); err != nil {
				return err
			}
			now := time.Now().UTC()
			preview, err := lifecycledomain.EvaluatePurge(scope, days, now)
			if err != nil {
				return err
			}
			if !preview.Allowed {
				return lifecycledomain.ErrConfirmation
			}
			var stamp, expires string
			var retained int
			err = tx.QueryRowContext(ctx, "SELECT scope_hash,retention_days,expires_at FROM lifecycle_confirmations WHERE token_hash=? AND entry_id=? AND consumed_by IS NULL", request.Confirmation, request.EntryID).Scan(&stamp, &retained, &expires)
			if errors.Is(err, sql.ErrNoRows) {
				return lifecycledomain.ErrConfirmation
			}
			if err != nil {
				return err
			}
			deadline, err := time.Parse(time.RFC3339Nano, expires)
			if err != nil || !now.Before(deadline) || stamp != preview.Stamp || retained != days {
				return lifecycledomain.ErrConfirmation
			}
			if err = sealPurge(ctx, tx, scope, task); err != nil {
				return err
			}
			_, err = tx.ExecContext(ctx, "UPDATE lifecycle_confirmations SET consumed_by=? WHERE token_hash=?", task, request.Confirmation)
			return err
		}
	}
}

// PurgeSnapshotAdmission 只与已确认批次的原子准入回调组合使用。
// Confirmation 在此表示冻结范围摘要；不会生成或消费单项清理令牌。
func (repo *repository) PurgeSnapshotAdmission(request lifecycledomain.Request, days int) jobsports.Admission {
	return func(task string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			if request.Operation != "purge" || lifecycledomain.ValidateRequest(request) != nil {
				return lifecycledomain.ErrValidation
			}
			scope, err := purgeScope(ctx, tx, request.EntryID)
			if errors.Is(err, lifecycledomain.ErrNotFound) || errors.Is(err, lifecycledomain.ErrConflict) {
				return lifecycledomain.ErrScopeChanged
			}
			if err != nil {
				return err
			}
			if err = blockChanges(ctx, tx, scope.Root.Target.ComicID, task); err != nil {
				return err
			}
			now := time.Now().UTC()
			preview, err := lifecycledomain.EvaluatePurge(scope, days, now)
			if err != nil {
				return err
			}
			if err = lifecycledomain.ValidatePurgeSnapshot(request.Confirmation, preview); err != nil {
				return err
			}
			return sealPurge(ctx, tx, scope, task)
		}
	}
}

func sealPurge(ctx context.Context, tx *sql.Tx, scope lifecycledomain.PurgeScope, task string) error {
	if _, err := tx.ExecContext(ctx, "INSERT INTO lifecycle_runs(task_id,entry_id,comic_id,operation) VALUES(?,?,?,'purge')", task, scope.Root.ID, scope.Root.Target.ComicID); err != nil {
		return err
	}
	for _, item := range scope.Items {
		if _, err := tx.ExecContext(ctx, "INSERT INTO lifecycle_purge_entries VALUES(?,?,?)", task, item.Entry.ID, item.PlanHash); err != nil {
			return err
		}
	}
	return nil
}

func (repo *repository) BeginPurge(ctx context.Context, task string) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	items, err := purgeRun(ctx, tx, task, "trashed")
	if err != nil {
		return err
	}
	now := time.Now().UTC()
	for _, item := range items {
		current, err := target(ctx, tx, item.Entry.Target.Type, item.Entry.Target.ID)
		if err != nil {
			return err
		}
		if current.State != "trashed" || current.ComicID != item.Entry.Target.ComicID {
			return lifecycledomain.ErrConflict
		}
		if _, err := tx.ExecContext(ctx, "UPDATE lifecycle_entries SET state='purging',task_id=?,updated_at=? WHERE id=?", task, now.Format(jobsdomain.TimestampFormat), item.Entry.ID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "UPDATE "+targetTable(item.Entry.Target.Type)+" SET lifecycle_state='purging' WHERE id=?", item.Entry.Target.ID); err != nil {
			return err
		}
	}
	return tx.Commit()
}
