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

func (repo *repository) Admission(expected lifecycledomain.Target, request lifecycledomain.Request, stamp string) jobsports.Admission {
	return func(taskID string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			if err := lifecycledomain.ValidateRequest(request); err != nil {
				return err
			}
			current, err := target(ctx, tx, expected.Type, expected.ID)
			if stamp != "" && request.Operation == "trash" && errors.Is(err, lifecycledomain.ErrNotFound) {
				return lifecycledomain.ErrScopeChanged
			}
			if err != nil {
				return err
			}
			if current.ComicID != expected.ComicID || current.ChapterID != expected.ChapterID {
				if stamp != "" {
					return lifecycledomain.ErrScopeChanged
				}
				return lifecycledomain.ErrConflict
			}
			active, err := ancestorActive(ctx, tx, current)
			if err != nil {
				return err
			}
			var sealedCandidates []lifecycledomain.FileCandidate
			if stamp != "" && request.Operation == "trash" {
				snapshot, files, readErr := trashScopeSnapshot(ctx, tx, current.Type, current.ID)
				if errors.Is(readErr, lifecycledomain.ErrNotFound) {
					return lifecycledomain.ErrScopeChanged
				}
				if readErr != nil {
					return readErr
				}
				if err = lifecycledomain.ValidateTrashStamp(stamp, snapshot.Stamp); err != nil {
					return err
				}
				sealedCandidates = files
			}
			if stamp != "" && request.Operation == "restore" {
				entry, readErr := scanEntry(tx.QueryRowContext(ctx, "SELECT "+entryColumns+" FROM lifecycle_entries WHERE id=?", request.EntryID))
				if readErr != nil {
					return readErr
				}
				var plan string
				if err = tx.QueryRowContext(ctx, "SELECT plan_json FROM lifecycle_entries WHERE id=?", request.EntryID).Scan(&plan); err != nil {
					return err
				}
				if err = lifecycledomain.ValidateRestoreStamp(stamp, lifecycledomain.EntryStamp(entry, plan, current.State), active); err != nil {
					return err
				}
			}
			if err = lifecycledomain.ValidateTransition(request.Operation, current.State, active); err != nil {
				return err
			}
			if err = blockChanges(ctx, tx, current.ComicID, taskID); err != nil {
				return err
			}
			if request.Operation == "restore" {
				if _, err = restoreCapacity(ctx, tx, current); err != nil {
					return err
				}
			}
			now := time.Now().UTC().Format(jobsdomain.TimestampFormat)
			entryID := request.EntryID
			state := "restoring"
			if request.Operation == "trash" {
				entryID = taskID
				state = "trashing"
				items := sealedCandidates
				if stamp == "" {
					items, err = candidates(ctx, tx, current)
				}
				if err != nil {
					return err
				}
				plan, err := encodeCandidates(items)
				if err != nil {
					return err
				}
				_, err = tx.ExecContext(ctx, "INSERT INTO lifecycle_entries(id,target_type,target_id,comic_id,chapter_id,title,state,plan_json,created_at,updated_at,origin,task_id) VALUES(?,?,?,?,?,?,'trashing',?,?,?,'native',?)", entryID, current.Type, current.ID, current.ComicID, current.ChapterID, current.Title, plan, now, now, taskID)
				if err != nil {
					return err
				}
				if err = captureCover(ctx, tx, current, entryID, items); err != nil {
					return err
				}
			} else {
				result, err := tx.ExecContext(ctx, "UPDATE lifecycle_entries SET state='restoring',task_id=?,updated_at=? WHERE id=? AND target_type=? AND target_id=? AND state='trashed'", taskID, now, entryID, current.Type, current.ID)
				if err != nil {
					return err
				}
				count, err := result.RowsAffected()
				if err != nil {
					return err
				}
				if count != 1 {
					return lifecycledomain.ErrConflict
				}
			}
			if _, err = tx.ExecContext(ctx, "INSERT INTO lifecycle_runs(task_id,entry_id,comic_id,operation) VALUES(?,?,?,?)", taskID, entryID, current.ComicID, request.Operation); err != nil {
				return err
			}
			if _, err = tx.ExecContext(ctx, "UPDATE "+targetTable(current.Type)+" SET lifecycle_state=? WHERE id=?", state, current.ID); err != nil {
				return err
			}
			if request.Operation == "trash" {
				return repo.park(ctx, tx, current)
			}
			return nil
		}
	}
}

func blockChanges(ctx context.Context, tx *sql.Tx, comic, exclude string) error {
	return blockChangesForOwner(ctx, tx, comic, exclude, "")
}

func blockChangesForOwner(ctx context.Context, tx *sql.Tx, comic, exclude, owner string) error {
	queries := []struct {
		sql  string
		args []any
	}{
		{"SELECT COUNT(*) FROM tasks WHERE comic_id=? AND id<>? AND status IN('queued','running','cancelling')", []any{comic, exclude}},
		{"SELECT COUNT(*) FROM lifecycle_runs WHERE comic_id=? AND completed=0 AND task_id<>?", []any{comic, owner}},
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
			return lifecycledomain.ErrConflict
		}
	}
	return nil
}
