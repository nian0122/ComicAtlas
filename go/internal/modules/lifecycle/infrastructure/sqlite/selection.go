package sqlite

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"database/sql"
	"encoding/json"
)

func entrySelectionQuery(selection lifecycledomain.EntrySelection) (string, []any) {
	from := "json_each(?) selected"
	ids, _ := json.Marshal(selection.IDs)
	args := []any{string(ids)}
	if selection.Mode == "filter" {
		excluded, _ := json.Marshal(selection.ExcludedIDs)
		from = `(SELECT id value FROM lifecycle_entries WHERE state='trashed' AND instr(COMIC_LOWER(title),COMIC_LOWER(?))>0 AND (?='' OR target_type=?)
 AND id NOT IN(SELECT value FROM json_each(?) WHERE type='text') ORDER BY id LIMIT ?) selected`
		args = []any{selection.Filter.Keyword, selection.Filter.TargetType, selection.Filter.TargetType, string(excluded), lifecycledomain.MaxSelectedEntries + 1}
	}
	return `SELECT selected.value,COALESCE(e.target_type,''),COALESCE(e.target_id,''),COALESCE(e.comic_id,''),COALESCE(e.chapter_id,''),COALESCE(e.title,''),COALESCE(e.state,''),
 COALESCE(e.created_at,''),COALESCE(e.updated_at,''),COALESCE(e.origin,''),COALESCE(e.task_id,''),COALESCE(e.plan_json,''),
 COALESCE(CASE e.target_type WHEN 'comic' THEN c.lifecycle_state WHEN 'chapter' THEN ch.lifecycle_state ELSE m.lifecycle_state END,''),
 COALESCE(c.lifecycle_state,''),COALESCE(ch.lifecycle_state,''),
 COALESCE((SELECT id FROM lifecycle_entries parent WHERE parent.target_type='comic' AND parent.target_id=e.comic_id AND parent.state='trashed' AND parent.state NOT IN('restored','purged','detached')),''),
 COALESCE((SELECT id FROM lifecycle_entries parent WHERE parent.target_type='chapter' AND parent.target_id=e.chapter_id AND parent.state='trashed' AND parent.state NOT IN('restored','purged','detached')),'')
 FROM ` + from + ` LEFT JOIN lifecycle_entries e ON e.id=selected.value LEFT JOIN comics c ON c.id=e.comic_id LEFT JOIN chapters ch ON ch.id=e.chapter_id LEFT JOIN media m ON m.id=e.target_id AND e.target_type='media'`, args
}
func (repo *repository) SelectEntries(ctx context.Context, selection lifecycledomain.EntrySelection) ([]lifecycledomain.SelectedEntry, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	result, err := selectEntries(ctx, tx, selection)
	if err != nil {
		return nil, err
	}
	return result, tx.Commit()
}
func selectEntries(ctx context.Context, tx *sql.Tx, selection lifecycledomain.EntrySelection) ([]lifecycledomain.SelectedEntry, error) {
	query, args := entrySelectionQuery(selection)
	// 只排序有界 ID 选集；大文件清单按行读取取摘要，不能随完整载荷进入排序器。
	rows, err := tx.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []lifecycledomain.SelectedEntry{}
	for rows.Next() {
		var entry lifecycledomain.Entry
		var plan, own, comic, chapter, comicEntry, chapterEntry string
		if err = rows.Scan(&entry.ID, &entry.Target.Type, &entry.Target.ID, &entry.Target.ComicID, &entry.Target.ChapterID, &entry.Target.Title, &entry.State, &entry.CreatedAt, &entry.UpdatedAt, &entry.Origin, &entry.TaskID, &plan, &own, &comic, &chapter, &comicEntry, &chapterEntry); err != nil {
			return nil, err
		}
		result = append(result, lifecycledomain.SelectRestoreEntry(entry, plan, own, comic, chapter, comicEntry, chapterEntry))
		if len(result) > lifecycledomain.MaxSelectedEntries {
			return nil, lifecycledomain.ErrValidation
		}
	}
	if err = rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	return result, nil
}

func (repo *repository) RestoreTarget(ctx context.Context, id, stamp string) (lifecycledomain.Target, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return lifecycledomain.Target{}, err
	}
	defer tx.Rollback()
	entry, err := scanEntry(tx.QueryRowContext(ctx, "SELECT "+entryColumns+" FROM lifecycle_entries WHERE id=?", id))
	if err != nil {
		return lifecycledomain.Target{}, err
	}
	current, err := target(ctx, tx, entry.Target.Type, entry.Target.ID)
	if err != nil {
		return lifecycledomain.Target{}, err
	}
	if err = lifecycledomain.ValidateRestoreIdentity(entry.Target, current); err != nil {
		return lifecycledomain.Target{}, err
	}
	active, err := ancestorActive(ctx, tx, current)
	if err != nil {
		return lifecycledomain.Target{}, err
	}
	var plan string
	if err = tx.QueryRowContext(ctx, "SELECT plan_json FROM lifecycle_entries WHERE id=?", id).Scan(&plan); err != nil {
		return lifecycledomain.Target{}, err
	}
	if err = lifecycledomain.ValidateRestoreStamp(stamp, lifecycledomain.EntryStamp(entry, plan, current.State), active); err != nil {
		return lifecycledomain.Target{}, err
	}
	return entry.Target, tx.Commit()
}
