package sqlite

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"time"

	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

const columns = "id,type,status,stage,attempt,total_count,completed_count,error_code,comic_id,created_at,started_at,finished_at,source_path,title,manifest,title_explicit,target_type,target_id,request_json"

func scan(scanner interface{ Scan(...any) error }) (jobsdomain.Task, error) {
	var task jobsdomain.Task
	err := scanner.Scan(&task.ID, &task.Type, &task.Status, &task.Stage, &task.Attempt, &task.TotalCount, &task.CompletedCount, &task.ErrorCode, &task.ComicID, &task.CreatedAt, &task.StartedAt, &task.FinishedAt, &task.SourcePath, &task.Title, &task.Manifest, &task.TitleExplicit, &task.TargetType, &task.TargetID, &task.RequestJSON)
	task = jobsdomain.WithDiagnostics(task)
	if errors.Is(err, sql.ErrNoRows) {
		err = jobsdomain.ErrNotFound
	}
	return task, err
}

func (store *sqliteRepository) Get(ctx context.Context, id string) (jobsdomain.Task, error) {
	return scan(store.database.Reader.QueryRowContext(ctx, "SELECT "+columns+" FROM tasks WHERE id=?", id))
}

func (store *sqliteRepository) CreateTask(ctx context.Context, kind, source, title, key, requestHash string, explicit bool) (jobsdomain.Task, error) {
	return store.create(ctx, jobsdomain.ResourceTask{Type: kind, SourcePath: source, Title: title}, key, requestHash, explicit, nil)
}
func (store *sqliteRepository) CreateResourceTask(ctx context.Context, request jobsdomain.ResourceTask, key, hash string, admission jobsports.Admission) (jobsdomain.Task, error) {
	return store.create(ctx, request, key, hash, false, admission)
}
func (store *sqliteRepository) create(ctx context.Context, request jobsdomain.ResourceTask, key, requestHash string, explicit bool, admission jobsports.Admission) (jobsdomain.Task, error) {
	tx, err := store.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	defer tx.Rollback()
	id, err := store.createInTx(ctx, tx, request, key, requestHash, explicit, admission)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	if err = tx.Commit(); err != nil {
		return jobsdomain.Task{}, err
	}
	return store.Get(ctx, id)
}
func (store *sqliteRepository) createInTx(ctx context.Context, tx *sql.Tx, request jobsdomain.ResourceTask, key, requestHash string, explicit bool, admission jobsports.Admission) (string, error) {
	kind, source, title := request.Type, request.SourcePath, request.Title
	var err error
	if key != "" {
		var hash, id string
		err = tx.QueryRowContext(ctx, "SELECT request_hash,id FROM tasks WHERE idempotency_key=?", key).Scan(&hash, &id)
		if err == nil {
			if hash != requestHash {
				return "", jobsdomain.ErrConflict
			}
			return id, nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return "", err
		}
	}
	var active int
	if err = blockLifecycleTask(ctx, tx, kind, request.ComicID, ""); err != nil {
		return "", err
	}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE status IN ('queued','running','cancelling')").Scan(&active); err != nil {
		return "", err
	}
	if jobsdomain.QueueFull(active) {
		return "", jobsdomain.ErrFull
	}
	if kind == "ai_analysis" {
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE type='ai_analysis' AND status IN('queued','running','cancelling')").Scan(&active); err != nil {
			return "", err
		}
		if jobsdomain.AnalysisQueueFull(active) {
			return "", jobsdomain.ErrFull
		}
	}
	if kind == "command_batch" || kind == "import_batch" {
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE type IN('command_batch','import_batch') AND status IN('queued','running','cancelling')").Scan(&active); err != nil {
			return "", err
		}
		if jobsdomain.BatchParentsFull(active) {
			return "", jobsdomain.ErrFull
		}
	}
	if source != "" {
		if err = blockImportSources(ctx, tx, source, ""); err != nil {
			return "", err
		}
		if err = blockReservedImportSources(ctx, tx, source, request.ReservationBatchID, "", request.ReservationPosition); err != nil {
			return "", err
		}
		active = 0
	} else {
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE type=? AND target_type=? AND target_id=? AND status IN ('queued','running','cancelling')", kind, request.TargetType, request.TargetID).Scan(&active); err != nil {
			return "", err
		}
	}
	if active > 0 {
		return "", jobsdomain.ErrTargetActive
	}
	var token [16]byte
	if _, err = rand.Read(token[:]); err != nil {
		return "", err
	}
	id := "task_" + hex.EncodeToString(token[:])
	comicID := request.ComicID
	if source != "" {
		comicID = "comic_" + hex.EncodeToString(token[:])
	}
	var payload any
	if request.Payload != "" {
		payload = request.Payload
	}
	var idempotency any
	if key != "" {
		idempotency = key
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO tasks(id,type,source_path,title,comic_id,status,stage,idempotency_key,request_hash,created_at,title_explicit,target_type,target_id,request_json)
		VALUES(?,?,?,?,?,'queued','queued',?,?,?,?,?,?,?)`, id, kind, source, title, comicID, idempotency, requestHash, time.Now().UTC().Format(jobsdomain.TimestampFormat), explicit, request.TargetType, request.TargetID, payload)
	if err != nil {
		return "", err
	}
	if admission != nil {
		if err = admission(id)(ctx, tx); err != nil {
			return "", err
		}
	}
	return id, nil
}

func (store *sqliteRepository) Claim(ctx context.Context) (jobsdomain.Task, error) {
	tx, err := store.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	defer tx.Rollback()
	var id, kind, previousStage string
	var manifest bool
	if err = tx.QueryRowContext(ctx, "SELECT id,type,manifest IS NOT NULL,stage FROM tasks WHERE status='queued' AND type NOT IN('command_batch','import_batch') AND (type='ai_analysis')=? ORDER BY created_at,id LIMIT 1", store.analysis).Scan(&id, &kind, &manifest, &previousStage); errors.Is(err, sql.ErrNoRows) {
		return jobsdomain.Task{}, jobsdomain.ErrNotFound
	} else if err != nil {
		return jobsdomain.Task{}, err
	}
	stage := jobsdomain.StartStage(kind, manifest)
	if !manifest && (kind == "directory_import" || kind == "archive_import") && jobsdomain.ImportHasMoved(previousStage) {
		stage = "normalizing"
	}
	task, err := scan(tx.QueryRowContext(ctx, "UPDATE tasks SET status='running',stage=?,attempt=attempt+1,started_at=?,finished_at=NULL,error_code=NULL WHERE id=? RETURNING "+columns, stage, time.Now().UTC().Format(jobsdomain.TimestampFormat), id))
	if err != nil {
		return task, err
	}
	return task, tx.Commit()
}

func (store *sqliteRepository) Recover(ctx context.Context) error {
	tx, err := store.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, "SELECT id,type,status FROM tasks WHERE status IN('running','cancelling') AND (type='ai_analysis')=?", store.analysis)
	if err != nil {
		return err
	}
	tasks := []jobsdomain.Task{}
	for rows.Next() {
		var task jobsdomain.Task
		if err = rows.Scan(&task.ID, &task.Type, &task.Status); err != nil {
			rows.Close()
			return err
		}
		tasks = append(tasks, task)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, task := range tasks {
		state := jobsdomain.RecoveredState(task)
		var finished any
		if state == "cancelled" {
			finished = time.Now().UTC().Format(jobsdomain.TimestampFormat)
		}
		if _, err = tx.ExecContext(ctx, "UPDATE tasks SET status=?,finished_at=? WHERE id=?", state, finished, task.ID); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (store *sqliteRepository) UpdateProgress(ctx context.Context, id, stage string, total, completed int) error {
	var countValue any = total
	if total < 0 {
		countValue = nil
	}
	result, err := store.database.Writer.ExecContext(ctx, "UPDATE tasks SET stage=?,total_count=?,completed_count=? WHERE id=? AND status='running'", stage, countValue, completed, id)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return context.Canceled
	}
	return nil
}

func (store *sqliteRepository) Publishing(ctx context.Context, id, manifest string) error {
	result, err := store.database.Writer.ExecContext(ctx, "UPDATE tasks SET stage='publishing',manifest=? WHERE id=? AND status='running'", manifest, id)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return context.Canceled
	}
	return nil
}

func (store *sqliteRepository) Complete(ctx context.Context, id string, completion jobsports.Completion) error {
	tx, err := store.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return jobsdomain.CompletionError("BEGIN", err)
	}
	defer tx.Rollback()
	var status string
	if err = tx.QueryRowContext(ctx, "SELECT status FROM tasks WHERE id=?", id).Scan(&status); err != nil {
		return jobsdomain.CompletionError("STATUS", err)
	}
	if status != "running" {
		return jobsdomain.CompletionError("STATE", jobsdomain.ErrConflict)
	}
	if err = completion.Persist(ctx, tx); err != nil {
		return jobsdomain.CompletionError("BUSINESS", err)
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM import_source_claims WHERE task_id=?", id); err != nil {
		return jobsdomain.CompletionError("BUSINESS", err)
	}
	if _, err = tx.ExecContext(ctx, "UPDATE tasks SET status='succeeded',stage='completed',completed_count=COALESCE(total_count,completed_count),finished_at=? WHERE id=?", time.Now().UTC().Format(jobsdomain.TimestampFormat), id); err != nil {
		return jobsdomain.CompletionError("TASK", err)
	}
	if err = tx.Commit(); err != nil {
		return jobsdomain.CompletionError("COMMIT", err)
	}
	return nil
}

func (store *sqliteRepository) Finish(ctx context.Context, id, status, code string, persist persistence.WriteAction) (jobsdomain.Task, error) {
	var terminal jobsdomain.Task
	tx, err := store.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return terminal, err
	}
	defer tx.Rollback()
	var current string
	if err = tx.QueryRowContext(ctx, "SELECT status FROM tasks WHERE id=?", id).Scan(&current); err != nil {
		return terminal, err
	}
	final, failure, err := jobsdomain.FinishState(current, status, code)
	if err != nil {
		return terminal, err
	}
	if final == jobsdomain.TaskStatusFailed && persist != nil {
		if err = persist(ctx, tx); err != nil {
			return terminal, err
		}
	}
	terminal, err = scanPublic(tx.QueryRowContext(ctx, "UPDATE tasks SET status=?,error_code=?,finished_at=? WHERE id=? RETURNING "+publicColumns, final, failure, time.Now().UTC().Format(jobsdomain.TimestampFormat), id))
	if err != nil {
		return terminal, err
	}
	if !jobsdomain.ImportHasMoved(terminal.Stage) {
		if _, err = tx.ExecContext(ctx, "DELETE FROM import_source_claims WHERE task_id=?", id); err != nil {
			return terminal, err
		}
	}
	return terminal, tx.Commit()
}

func (store *sqliteRepository) Cancel(ctx context.Context, id string) error {
	tx, err := store.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var task jobsdomain.Task
	if err = tx.QueryRowContext(ctx, "SELECT type,status,stage FROM tasks WHERE id=?", id).Scan(&task.Type, &task.Status, &task.Stage); errors.Is(err, sql.ErrNoRows) {
		return jobsdomain.ErrNotFound
	} else if err != nil {
		return err
	}
	if task.Status == "cancelled" || task.Status == "cancelling" {
		return nil
	}
	if !jobsdomain.CanCancel(task) {
		return jobsdomain.ErrConflict
	}
	state := jobsdomain.CancellationState(task)
	var finished any
	if state == "cancelled" {
		finished = time.Now().UTC().Format(jobsdomain.TimestampFormat)
	}
	_, err = tx.ExecContext(ctx, "UPDATE tasks SET status=?,finished_at=? WHERE id=?", state, finished, id)
	if err != nil {
		return err
	}
	return tx.Commit()
}

func (store *sqliteRepository) Retry(ctx context.Context, id string) error {
	return store.RetryGuarded(ctx, id, nil)
}

func (store *sqliteRepository) RetryGuarded(ctx context.Context, id string, guard persistence.WriteAction) error {
	tx, err := store.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var status, source, kind, targetType, targetID, comicID, stage string
	if err = tx.QueryRowContext(ctx, "SELECT status,source_path,type,target_type,target_id,comic_id,stage FROM tasks WHERE id=?", id).Scan(&status, &source, &kind, &targetType, &targetID, &comicID, &stage); errors.Is(err, sql.ErrNoRows) {
		return jobsdomain.ErrNotFound
	} else if err != nil {
		return err
	}
	if !jobsdomain.CanRetryTask(jobsdomain.Task{Type: kind, Status: status, Stage: stage}) {
		return jobsdomain.ErrConflict
	}
	if source != "" {
		if err = blockImportSources(ctx, tx, source, id); err != nil {
			return err
		}
	}
	if (kind == "directory_move" || kind == "directory_preflight" || kind == "recovery_repair" || kind == "ai_analysis") && guard == nil {
		return jobsdomain.ErrConflict
	}
	if err = blockLifecycleTask(ctx, tx, kind, comicID, id); err != nil {
		return err
	}
	if jobsdomain.RetryMediaConflict(kind, "video_transcode") {
		visible, err := retryTargetVisible(ctx, tx, comicID, targetType, targetID)
		if err != nil {
			return err
		}
		if !visible {
			return jobsdomain.ErrConflict
		}
	}
	var count int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE status IN ('queued','running','cancelling')").Scan(&count); err != nil {
		return err
	}
	if jobsdomain.QueueFull(count) {
		return jobsdomain.ErrFull
	}
	if kind == "ai_analysis" {
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE type='ai_analysis' AND status IN('queued','running','cancelling')").Scan(&count); err != nil {
			return err
		}
		if jobsdomain.AnalysisQueueFull(count) {
			return jobsdomain.ErrFull
		}
	}
	if kind == "command_batch" || kind == "import_batch" {
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE type IN('command_batch','import_batch') AND id<>? AND status IN('queued','running','cancelling')", id).Scan(&count); err != nil {
			return err
		}
		if jobsdomain.BatchParentsFull(count) {
			return jobsdomain.ErrFull
		}
	}
	var linked int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM batch_preview_items WHERE child_task_id=?", id).Scan(&linked); err != nil {
		return err
	}
	if linked > 0 {
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks t JOIN batch_preview_items i ON i.child_task_id=t.id WHERE t.id<>? AND t.status IN('queued','running','cancelling')", id).Scan(&count); err != nil {
			return err
		}
		if jobsdomain.BatchChildrenFull(count) {
			return jobsdomain.ErrFull
		}
	}
	if source != "" {
		err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE source_path=? AND status IN ('queued','running','cancelling')", source).Scan(&count)
	} else {
		err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tasks WHERE type=? AND target_type=? AND target_id=? AND status IN ('queued','running','cancelling')", kind, targetType, targetID).Scan(&count)
	}
	if err != nil {
		return err
	}
	if count != 0 {
		return jobsdomain.ErrConflict
	}
	if jobsdomain.RetryMediaConflict(kind, "video_transcode") {
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM metadata_refresh_runs WHERE comic_id=? AND task_id<>? AND applied=1 AND completed=0", comicID, id).Scan(&count); err != nil {
			return err
		}
		if count > 0 {
			return jobsdomain.ErrConflict
		}
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM upload_sessions WHERE comic_id=? AND status IN ('submitted','activated') AND task_id<>?", comicID, id).Scan(&count); err != nil {
			return err
		}
		if count > 0 {
			return jobsdomain.ErrConflict
		}
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM video_transcode_items i JOIN tasks t ON t.id=i.task_id WHERE t.comic_id=? AND t.id<>? AND i.status='prepared'", comicID, id).Scan(&count); err != nil {
			return err
		}
		if count > 0 {
			return jobsdomain.ErrConflict
		}
	}
	rows, err := tx.QueryContext(ctx, "SELECT type FROM tasks WHERE comic_id=? AND id<>? AND type IN ('archive_export','image_lq','hq_release','video_transcode','media_upload','metadata_refresh') AND status IN ('queued','running','cancelling')", comicID, id)
	if err != nil {
		return err
	}
	for rows.Next() {
		var other string
		if err = rows.Scan(&other); err != nil {
			rows.Close()
			return err
		}
		if jobsdomain.RetryMediaConflict(kind, other) {
			rows.Close()
			return jobsdomain.ErrConflict
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	if guard != nil {
		if err = guard(ctx, tx); err != nil {
			return err
		}
	}
	if _, err = tx.ExecContext(ctx, "UPDATE tasks SET status='queued',error_code=NULL,finished_at=NULL WHERE id=?", id); err != nil {
		return err
	}
	return tx.Commit()
}

type sqliteRepository struct {
	database *persistence.Database
	analysis bool
}

func New(database *persistence.Database) *sqliteRepository {
	return &sqliteRepository{database: database}
}

// NewAnalysis 独立领取和恢复 AI 通道，不占用普通文件任务的执行线程。
func NewAnalysis(database *persistence.Database) *sqliteRepository {
	return &sqliteRepository{database: database, analysis: true}
}

var _ jobsports.Repository = (*sqliteRepository)(nil)

func (store *sqliteRepository) Requeue(ctx context.Context, id string) error {
	_, err := store.database.Writer.ExecContext(ctx, "UPDATE tasks SET status=CASE WHEN status='cancelling' THEN 'cancelled' ELSE 'queued' END WHERE id=? AND status IN ('running','cancelling')", id)
	return err
}
