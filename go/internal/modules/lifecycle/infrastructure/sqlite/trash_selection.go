package sqlite

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

func trashRootsQuery(kind string) string {
	if kind == "chapter" {
		return `SELECT ch.id,ch.comic_id,ch.id chapter_id,ch.title,COALESCE(ch.catalog_id,'') catalog_id,'' media_type,COALESCE(r.reaction,'') reaction
 FROM active_chapters ch LEFT JOIN chapter_reactions r ON r.target_id=ch.id`
	}
	return `SELECT m.id,ch.comic_id,m.chapter_id,m.file_name title,'' catalog_id,m.media_type,COALESCE(r.reaction,'') reaction
 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id LEFT JOIN media_reactions r ON r.target_id=m.id`
}

func trashSelectionQuery(kind string, selection lifecycledomain.TargetSelection) (string, []any) {
	if selection.Mode == "ids" {
		ids, _ := json.Marshal(selection.IDs)
		return "SELECT value FROM json_each(?) ORDER BY value", []any{string(ids)}
	}
	filter := selection.Filter
	where := " WHERE instr(COMIC_LOWER(q.title),COMIC_LOWER(?))>0"
	args := []any{filter.Keyword}
	if filter.ComicID != "" {
		where += " AND q.comic_id=?"
		args = append(args, filter.ComicID)
	}
	if filter.ChapterID != "" {
		where += " AND q.chapter_id=?"
		args = append(args, filter.ChapterID)
	}
	if filter.MediaType != "" {
		where += " AND q.media_type=?"
		args = append(args, filter.MediaType)
	}
	if filter.Reaction != "" {
		reactions := "media_reactions"
		if kind == "chapter" {
			reactions = "chapter_reactions"
		}
		where += " AND q.id IN(SELECT target_id FROM " + reactions
		if filter.Reaction != "any" {
			where += " WHERE reaction=?"
			args = append(args, filter.Reaction)
		}
		where += ")"
	}
	excluded, _ := json.Marshal(selection.ExcludedIDs)
	where += " AND q.id NOT IN(SELECT value FROM json_each(?) WHERE type='text')"
	args = append(args, string(excluded), lifecycledomain.MaxSelectedTargets+1)
	return "SELECT q.id FROM (" + trashRootsQuery(kind) + ") q" + where + " ORDER BY q.id LIMIT ?", args
}

func (repo *repository) SelectTrashTargets(ctx context.Context, kind string, selection lifecycledomain.TargetSelection) ([]lifecycledomain.SelectedTarget, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	query, args := trashSelectionQuery(kind, selection)
	rows, err := tx.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	ids := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, id)
		if len(ids) > lifecycledomain.MaxSelectedTargets {
			rows.Close()
			return nil, lifecycledomain.ErrValidation
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	result := make([]lifecycledomain.SelectedTarget, 0, len(ids))
	for _, id := range ids {
		value, readErr := trashSnapshot(ctx, tx, kind, id)
		if readErr != nil {
			value = lifecycledomain.SelectedTarget{Target: lifecycledomain.Target{Type: kind, ID: id}}
			code, expected := lifecycledomain.TrashSelectionBlocker(readErr)
			if !expected {
				return nil, readErr
			}
			value.Blocker = code
		}
		result = append(result, value)
	}
	return result, tx.Commit()
}

func (repo *repository) TrashTarget(ctx context.Context, kind, id, stamp string) (lifecycledomain.Target, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return lifecycledomain.Target{}, err
	}
	defer tx.Rollback()
	value, err := trashSnapshot(ctx, tx, kind, id)
	if errors.Is(err, lifecycledomain.ErrNotFound) {
		return value.Target, lifecycledomain.ErrScopeChanged
	}
	if err != nil {
		return value.Target, err
	}
	if err = lifecycledomain.ValidateTrashStamp(stamp, value.Stamp); err != nil {
		return value.Target, err
	}
	return value.Target, tx.Commit()
}
