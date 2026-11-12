package sqlite

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"strings"
)

func scanPurgeItem(row interface{ Scan(...any) error }, sealed bool) (lifecycledomain.PurgeItem, int, error) {
	var value lifecycledomain.PurgeItem
	var plan, expected string
	e := &value.Entry
	var taskID sql.NullString
	fields := []any{&e.ID, &e.Target.Type, &e.Target.ID, &e.Target.ComicID, &e.Target.ChapterID, &e.Target.Title, &e.State, &e.CreatedAt, &e.UpdatedAt, &e.Origin, &taskID, &plan}
	if sealed {
		fields = append(fields, &expected)
	}
	if err := row.Scan(fields...); err != nil {
		return value, 0, err
	}
	if taskID.Valid {
		e.TaskID = taskID.String
	}
	if len(plan) > lifecycledomain.MaxJournalBytes || json.Unmarshal([]byte(plan), &value.Candidates) != nil || lifecycledomain.ValidateCandidates(value.Candidates) != nil {
		return value, 0, lifecycledomain.ErrJournal
	}
	digest := sha256.Sum256([]byte(plan))
	value.PlanHash = hex.EncodeToString(digest[:])
	if sealed && value.PlanHash != expected {
		return value, 0, lifecycledomain.ErrJournal
	}
	return value, len(plan), nil
}

func purgeScope(ctx context.Context, tx *sql.Tx, id string) (lifecycledomain.PurgeScope, error) {
	var value lifecycledomain.PurgeScope
	root, err := scanEntry(tx.QueryRowContext(ctx, "SELECT "+entryColumns+" FROM lifecycle_entries WHERE id=?", id))
	if err != nil {
		return value, err
	}
	if root.State != "trashed" {
		return value, lifecycledomain.ErrConflict
	}
	current, err := target(ctx, tx, root.Target.Type, root.Target.ID)
	if err != nil {
		return value, err
	}
	if current.State != "trashed" || current.ComicID != root.Target.ComicID {
		return value, lifecycledomain.ErrConflict
	}
	where := "id=?"
	args := []any{id}
	if root.Target.Type == "comic" {
		where = "comic_id=?"
		args = []any{root.Target.ComicID}
	}
	if root.Target.Type == "chapter" {
		where = "comic_id=? AND (id=? OR (target_type='media' AND chapter_id=?))"
		args = []any{root.Target.ComicID, id, root.Target.ID}
	}
	rows, err := tx.QueryContext(ctx, "SELECT "+entryColumns+",plan_json FROM lifecycle_entries WHERE ("+where+") AND state NOT IN('restored','purged','detached') ORDER BY id LIMIT ?", append(args, lifecycledomain.MaxFiles+1)...)
	if err != nil {
		return value, err
	}
	defer rows.Close()
	value.Root = root
	totalBytes, totalFiles := 0, 0
	for rows.Next() {
		item, size, err := scanPurgeItem(rows, false)
		if err != nil {
			return value, err
		}
		totalBytes += size
		totalFiles += len(item.Candidates)
		if totalBytes > lifecycledomain.MaxJournalBytes || totalFiles > lifecycledomain.MaxFiles || len(value.Items) >= lifecycledomain.MaxFiles {
			return value, lifecycledomain.ErrValidation
		}
		value.Items = append(value.Items, item)
	}
	return value, rows.Err()
}

func (repo *repository) PurgeScope(ctx context.Context, id string) (lifecycledomain.PurgeScope, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return lifecycledomain.PurgeScope{}, err
	}
	defer tx.Rollback()
	value, err := purgeScope(ctx, tx, id)
	if err == nil {
		err = blockChanges(ctx, tx, value.Root.Target.ComicID, "")
	}
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}

func purgeRun(ctx context.Context, tx interface {
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
}, task, state string) ([]lifecycledomain.PurgeItem, error) {
	if state != "trashed" && state != "purging" {
		return nil, lifecycledomain.ErrConflict
	}
	columns := "e." + strings.ReplaceAll(entryColumns, ",", ",e.")
	rows, err := tx.QueryContext(ctx, "SELECT "+columns+",e.plan_json,p.plan_hash FROM lifecycle_purge_entries p JOIN lifecycle_entries e ON e.id=p.entry_id WHERE p.task_id=? ORDER BY e.id LIMIT ?", task, lifecycledomain.MaxFiles+1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []lifecycledomain.PurgeItem{}
	totalBytes, totalFiles := 0, 0
	for rows.Next() {
		item, size, err := scanPurgeItem(rows, true)
		if err != nil {
			return nil, err
		}
		totalBytes += size
		totalFiles += len(item.Candidates)
		if item.Entry.State != state || state == "purging" && item.Entry.TaskID != task {
			return nil, lifecycledomain.ErrConflict
		}
		if totalBytes > lifecycledomain.MaxJournalBytes || totalFiles > lifecycledomain.MaxFiles || len(items) >= lifecycledomain.MaxFiles {
			return nil, lifecycledomain.ErrValidation
		}
		items = append(items, item)
	}
	if err = rows.Err(); err != nil {
		return nil, err
	}
	if len(items) == 0 {
		return nil, lifecycledomain.ErrJournal
	}
	return items, nil
}
