package sqlite

import (
	schedulerports "comicatlas.local/backend/internal/modules/scheduler/application/ports"
	schedulerdomain "comicatlas.local/backend/internal/modules/scheduler/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"time"
)

type repository struct{ database *persistence.Database }

func New(database *persistence.Database) *repository { return &repository{database: database} }

var _ schedulerports.Repository = (*repository)(nil)

func (repo *repository) LoadSettings(ctx context.Context) (map[string]schedulerdomain.Settings, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT schedule_id,enabled,interval_seconds FROM scheduler_settings")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	settings := map[string]schedulerdomain.Settings{}
	for rows.Next() {
		var id string
		var enabled int
		var seconds int64
		if err = rows.Scan(&id, &enabled, &seconds); err != nil {
			return nil, err
		}
		settings[id] = schedulerdomain.Settings{Enabled: enabled == 1, Interval: time.Duration(seconds) * time.Second}
	}
	return settings, rows.Err()
}

func (repo *repository) SaveSettings(ctx context.Context, id string, settings schedulerdomain.Settings, updatedAt string) error {
	enabled := 0
	if settings.Enabled {
		enabled = 1
	}
	_, err := repo.database.Writer.ExecContext(ctx, `INSERT INTO scheduler_settings(schedule_id,enabled,interval_seconds,updated_at) VALUES(?,?,?,?)
	 ON CONFLICT(schedule_id) DO UPDATE SET enabled=excluded.enabled,interval_seconds=excluded.interval_seconds,updated_at=excluded.updated_at`,
		id, enabled, int64(settings.Interval/time.Second), updatedAt)
	return err
}

// CreateRun 记录一次执行并裁剪该定时器的历史，保留最近 keep 条。
func (repo *repository) CreateRun(ctx context.Context, run schedulerdomain.Run, keep int) error {
	if keep < 1 {
		keep = 1
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "INSERT INTO scheduler_runs(id,schedule_id,trigger,started_at) VALUES(?,?,?,?)", run.ID, run.ScheduleID, run.Trigger, run.StartedAt); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `DELETE FROM scheduler_runs WHERE schedule_id=? AND id NOT IN (
	 SELECT id FROM scheduler_runs WHERE schedule_id=? ORDER BY started_at DESC,id DESC LIMIT ?)`, run.ScheduleID, run.ScheduleID, keep); err != nil {
		return err
	}
	return tx.Commit()
}

func (repo *repository) FinishRun(ctx context.Context, id, finishedAt string, processed int, failure string) error {
	var cause any
	if failure != "" {
		cause = failure
	}
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE scheduler_runs SET finished_at=?,processed=?,error=? WHERE id=?", finishedAt, processed, cause, id)
	return err
}

func (repo *repository) Runs(ctx context.Context, scheduleID string, limit int) ([]schedulerdomain.Run, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT id,schedule_id,trigger,started_at,finished_at,processed,error FROM scheduler_runs WHERE schedule_id=? ORDER BY started_at DESC,id DESC LIMIT ?", scheduleID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	runs := []schedulerdomain.Run{}
	for rows.Next() {
		var run schedulerdomain.Run
		var failure sql.NullString
		if err = rows.Scan(&run.ID, &run.ScheduleID, &run.Trigger, &run.StartedAt, &run.FinishedAt, &run.Processed, &failure); err != nil {
			return nil, err
		}
		if failure.Valid {
			value := failure.String
			run.Error = &value
		}
		runs = append(runs, run)
	}
	return runs, rows.Err()
}

// InterruptRuns 把上次进程遗留的未完成记录标记为中断。
func (repo *repository) InterruptRuns(ctx context.Context, finishedAt string) error {
	_, err := repo.database.Writer.ExecContext(ctx, "UPDATE scheduler_runs SET finished_at=?, error='interrupted' WHERE finished_at IS NULL", finishedAt)
	return err
}
