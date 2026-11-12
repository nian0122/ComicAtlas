package sqlite

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
)

// PlaceholderWriter 不生成结构、媒体关联或侧车；原目录保留供补全。
func (repo *sqliteRepository) PlaceholderWriter(comic, task, recordedAt string, presence recoverydomain.HQPresence) persistence.WriteAction {
	plan, prepareErr := recoverydomain.PreparePlaceholder(comic, task, recordedAt, presence)
	guard := repo.GuardAbsent(comic)
	return func(ctx context.Context, tx *sql.Tx) error {
		if prepareErr != nil {
			return prepareErr
		}
		if err := guard(ctx, tx); err != nil {
			return err
		}
		var valid bool
		if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM recovery_candidates c JOIN recovery_runs r ON r.task_id=c.task_id JOIN tasks t ON t.id=r.task_id WHERE c.task_id=? AND c.comic_id=? AND r.sealed=1 AND t.type='disk_recovery' AND t.status='running')`, task, comic).Scan(&valid); err != nil {
			return err
		}
		if !valid {
			return recoverydomain.ErrConflict
		}
		if _, err := tx.ExecContext(ctx, "INSERT INTO comics(id,title,created_at) VALUES(?,?,?)", comic, plan.Title, plan.CreatedAt); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, "INSERT INTO comic_recovery_placeholders(comic_id,task_id,reason,created_at,media_count,other_count,directory_count) VALUES(?,?,'metadata_missing',?,?,?,?)", comic, task, plan.CreatedAt, plan.Presence.Media, plan.Presence.Other, plan.Presence.Directories)
		return err
	}
}

func (repo *sqliteRepository) Placeholder(ctx context.Context, comic string) (value recoverydomain.Placeholder, err error) {
	if !recoverydomain.Key(comic) {
		return value, recoverydomain.ErrValidation
	}
	value.ComicID = comic
	err = repo.database.Reader.QueryRowContext(ctx, "SELECT task_id,reason,created_at,media_count,other_count,directory_count FROM comic_recovery_placeholders WHERE comic_id=?", comic).Scan(&value.TaskID, &value.Reason, &value.CreatedAt, &value.Presence.Media, &value.Presence.Other, &value.Presence.Directories)
	if errors.Is(err, sql.ErrNoRows) {
		err = recoverydomain.ErrMissing
	}
	if err != nil {
		value = recoverydomain.Placeholder{}
	}
	return value, err
}
