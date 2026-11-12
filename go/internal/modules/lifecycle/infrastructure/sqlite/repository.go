package sqlite

import (
	lifecycleports "comicatlas.local/backend/internal/modules/lifecycle/application/ports"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

type repository struct {
	database  *persistence.Database
	structure func(string) persistence.WriteAction
	media     func(string) persistence.WriteAction
}

func New(database *persistence.Database, structure, media func(string) persistence.WriteAction) *repository {
	return &repository{database: database, structure: structure, media: media}
}

var _ lifecycleports.Repository = (*repository)(nil)

const entryColumns = "id,target_type,target_id,comic_id,chapter_id,title,state,created_at,updated_at,origin,task_id"

func scanEntry(row interface{ Scan(...any) error }) (lifecycledomain.Entry, error) {
	var value lifecycledomain.Entry
	var taskID sql.NullString
	err := row.Scan(&value.ID, &value.Target.Type, &value.Target.ID, &value.Target.ComicID, &value.Target.ChapterID, &value.Target.Title, &value.State, &value.CreatedAt, &value.UpdatedAt, &value.Origin, &taskID)
	if taskID.Valid {
		value.TaskID = taskID.String
	}
	value.Target.State = value.State
	if errors.Is(err, sql.ErrNoRows) {
		err = lifecycledomain.ErrNotFound
	}
	return value, err
}

func (repo *repository) Entry(ctx context.Context, id string) (lifecycledomain.Entry, error) {
	return scanEntry(repo.database.Reader.QueryRowContext(ctx, "SELECT "+entryColumns+" FROM lifecycle_entries WHERE id=?", id))
}

func (repo *repository) Entries(ctx context.Context, query lifecycledomain.EntryQuery) (lifecycledomain.EntryPage, error) {
	value := lifecycledomain.EntryPage{Items: []lifecycledomain.Entry{}, Page: query.Page, PageSize: query.PageSize}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return value, err
	}
	defer tx.Rollback()
	where := " WHERE ((?='' AND state<>'detached') OR state=?) AND (?='' OR instr(COMIC_LOWER(title),COMIC_LOWER(?))>0)"
	args := []any{query.State, query.State, query.Keyword, query.Keyword}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM lifecycle_entries"+where, args...).Scan(&value.Total); err != nil {
		return value, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT "+entryColumns+" FROM lifecycle_entries"+where+" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?", append(args, query.PageSize, (query.Page-1)*query.PageSize)...)
	if err != nil {
		return value, err
	}
	for rows.Next() {
		entry, err := scanEntry(rows)
		if err != nil {
			rows.Close()
			return value, err
		}
		value.Items = append(value.Items, entry)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}

func (repo *repository) Run(ctx context.Context, id string) (lifecycledomain.Run, error) {
	var value lifecycledomain.Run
	var entryID, plan string
	err := repo.database.Reader.QueryRowContext(ctx, `SELECT r.entry_id,r.operation,e.plan_json,r.trash_move_started FROM lifecycle_runs r
	 JOIN lifecycle_entries e ON e.id=r.entry_id WHERE r.task_id=? AND r.completed=0
	 AND (e.task_id=r.task_id OR (r.operation='purge' AND e.state='trashed'))`, id).Scan(&entryID, &value.Operation, &plan, &value.TrashMoveStarted)
	if errors.Is(err, sql.ErrNoRows) {
		return value, lifecycledomain.ErrConflict
	}
	if err != nil {
		return value, err
	}
	if value.Operation != "purge" && (len(plan) > lifecycledomain.MaxJournalBytes || json.Unmarshal([]byte(plan), &value.Candidates) != nil || lifecycledomain.ValidateCandidates(value.Candidates) != nil) {
		return value, lifecycledomain.ErrJournal
	}
	value.Entry, err = repo.Entry(ctx, entryID)
	if err == nil && value.Operation == "purge" {
		value.PurgeItems, err = purgeRun(ctx, repo.database.Reader, id, value.Entry.State)
	}
	return value, err
}
