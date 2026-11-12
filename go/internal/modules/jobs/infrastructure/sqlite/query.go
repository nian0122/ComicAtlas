package sqlite

import (
	"context"
	"database/sql"
	"errors"

	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
)

func (repository *sqliteRepository) Events(ctx context.Context, taskID string, after int64, limit int) (jobsdomain.TaskEventPage, error) {
	if _, err := repository.Get(ctx, taskID); err != nil {
		return jobsdomain.TaskEventPage{}, err
	}
	rows, err := repository.database.Reader.QueryContext(ctx, `SELECT id,event_type,status,stage,attempt,total_count,completed_count,error_code,occurred_at FROM task_events WHERE task_id=? AND id>? ORDER BY id LIMIT ?`, taskID, after, limit+1)
	if err != nil {
		return jobsdomain.TaskEventPage{}, err
	}
	defer rows.Close()
	items := make([]jobsdomain.TaskEvent, 0, limit)
	for rows.Next() {
		var event jobsdomain.TaskEvent
		if err = rows.Scan(&event.ID, &event.Type, &event.Status, &event.Stage, &event.Attempt, &event.TotalCount, &event.CompletedCount, &event.ErrorCode, &event.OccurredAt); err != nil {
			return jobsdomain.TaskEventPage{}, err
		}
		items = append(items, event)
	}
	if err = rows.Err(); err != nil {
		return jobsdomain.TaskEventPage{}, err
	}
	page := jobsdomain.TaskEventPage{TaskID: taskID, Items: items}
	if len(items) > limit {
		next := items[limit-1].ID
		page.NextAfter = &next
		page.Items = items[:limit]
	}
	return page, nil
}

// 页面不读取可能很大的 manifest，也不装配源路径和内部提交参数。
const publicColumns = "id,type,status,stage,attempt,total_count,completed_count,error_code,comic_id,created_at,started_at,finished_at,title,target_type,target_id"

func scanPublic(scanner interface{ Scan(...any) error }) (jobsdomain.Task, error) {
	var task jobsdomain.Task
	err := scanner.Scan(&task.ID, &task.Type, &task.Status, &task.Stage, &task.Attempt, &task.TotalCount, &task.CompletedCount, &task.ErrorCode, &task.ComicID, &task.CreatedAt, &task.StartedAt, &task.FinishedAt, &task.Title, &task.TargetType, &task.TargetID)
	if errors.Is(err, sql.ErrNoRows) {
		err = jobsdomain.ErrNotFound
	}
	task = jobsdomain.WithDiagnostics(task)
	return task, err
}
func (store *sqliteRepository) Inspect(ctx context.Context, id string) (jobsdomain.Task, error) {
	return scanPublic(store.database.Reader.QueryRowContext(ctx, "SELECT "+publicColumns+" FROM tasks WHERE id=?", id))
}

func taskPredicate(filter jobsdomain.Filter, status string) (string, []any) {
	where := " WHERE 1=1"
	arguments := []any{}
	if filter.Type != "" {
		where += " AND type=?"
		arguments = append(arguments, filter.Type)
	}
	if filter.Keyword != "" {
		where += " AND instr(COMIC_LOWER(title||' '||id),COMIC_LOWER(?))>0"
		arguments = append(arguments, filter.Keyword)
	}
	if status != "" {
		where += " AND status=?"
		arguments = append(arguments, status)
	}
	return where, arguments
}
func (store *sqliteRepository) List(ctx context.Context, query jobsdomain.ListQuery) (jobsdomain.TaskPage, error) {
	result := jobsdomain.TaskPage{Items: []jobsdomain.Task{}, Page: query.Page, PageSize: query.PageSize}
	tx, err := store.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	where, arguments := taskPredicate(query.Filter, query.Status)
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks"+where, arguments...).Scan(&result.Total); err != nil {
		return result, err
	}
	order := "DESC"
	if query.Oldest {
		order = "ASC"
	}
	rows, err := tx.QueryContext(ctx, "SELECT "+publicColumns+" FROM tasks"+where+" ORDER BY created_at "+order+",id "+order+" LIMIT ? OFFSET ?", append(arguments, query.PageSize, (query.Page-1)*query.PageSize)...)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		task, err := scanPublic(rows)
		if err != nil {
			return result, err
		}
		result.Items = append(result.Items, task)
	}
	if err = rows.Err(); err != nil {
		return result, err
	}
	if err = rows.Close(); err != nil {
		return result, err
	}
	return result, tx.Commit()
}
func (store *sqliteRepository) Summary(ctx context.Context, filter jobsdomain.Filter) (jobsdomain.Summary, error) {
	var result jobsdomain.Summary
	where, arguments := taskPredicate(filter, "")
	err := store.database.Reader.QueryRowContext(ctx, `SELECT COUNT(*),COALESCE(SUM(status='queued'),0),COALESCE(SUM(status='running'),0),COALESCE(SUM(status='cancelling'),0),COALESCE(SUM(status='cancelled'),0),COALESCE(SUM(status='succeeded'),0),COALESCE(SUM(status='failed'),0) FROM tasks`+where, arguments...).Scan(&result.Total, &result.Queued, &result.Running, &result.Cancelling, &result.Cancelled, &result.Succeeded, &result.Failed)
	return result, err
}
