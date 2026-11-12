package sqlite

import (
	aiports "comicatlas.local/backend/internal/modules/ai/application/ports"
	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

var _ aiports.TaskRepository = (*Repository)(nil)

func (repo *Repository) Admission(comic string, request aidomain.AnalysisRequest) jobsports.Admission {
	return func(task string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			normalized, err := aidomain.NormalizeAnalysisRequest(comic, request)
			if err != nil || normalized != request {
				return aidomain.ErrValidation
			}
			snapshot, err := snapshotInTx(ctx, tx, comic, request.SampleCount)
			if err != nil {
				return err
			}
			if err = aidomain.ValidateAnalysisVersion(request, snapshot); err != nil {
				return err
			}
			data, err := encodeSnapshot(snapshot)
			if err != nil {
				return err
			}
			payload, err := json.Marshal(request)
			if err != nil {
				return err
			}
			result, err := tx.ExecContext(ctx, `INSERT INTO ai_runs(task_id,snapshot_json,sample_count) SELECT id,?,? FROM tasks WHERE id=? AND type='ai_analysis' AND comic_id=? AND target_id=? AND status='queued' AND request_json=?`, string(data), request.SampleCount, task, comic, comic, string(payload))
			if err != nil {
				return err
			}
			count, err := result.RowsAffected()
			if err != nil {
				return err
			}
			if count != 1 {
				return aidomain.ErrSource
			}
			return nil
		}
	}
}

type rowReader interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func readRun(ctx context.Context, query rowReader, task string) (value aidomain.Run, err error) {
	value.TaskID = task
	var raw string
	var comic, target, status string
	var attempt int
	var resultAttempt *int
	var request, candidate, completed *string
	err = query.QueryRowContext(ctx, `SELECT r.snapshot_json,r.sample_count,r.result_request_json,r.candidate_json,r.completed_at,r.task_attempt,t.comic_id,t.target_id,t.status,t.attempt FROM ai_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND t.type='ai_analysis'`, task).Scan(&raw, &value.SampleCount, &request, &candidate, &completed, &resultAttempt, &comic, &target, &status, &attempt)
	if errors.Is(err, sql.ErrNoRows) {
		return value, aidomain.ErrNotFound
	}
	if err != nil {
		return value, err
	}
	value.Snapshot, err = decodeSnapshot([]byte(raw))
	if err != nil {
		return value, err
	}
	if value.Snapshot.ComicID != comic || target != comic {
		return value, aidomain.ErrSource
	}
	if candidate != nil {
		result := &aidomain.AnalysisResult{TaskID: task}
		if status != "succeeded" || resultAttempt == nil || *resultAttempt != attempt || request == nil || completed == nil || len(*request) > aidomain.MaxManifestBytes || len(*candidate) > aidomain.MaxResponseBytes || json.Unmarshal([]byte(*request), &result.Request) != nil || json.Unmarshal([]byte(*candidate), &result.Candidate) != nil {
			return value, aidomain.ErrProtocol
		}
		if aidomain.ValidatePrepared(value.Snapshot, result.Request) != nil || aidomain.ValidateCandidate(result.Request, result.Candidate) != nil {
			return value, aidomain.ErrProtocol
		}
		if result.Request.RequestID != aidomain.AnalysisRequestID(task, attempt) {
			return value, aidomain.ErrProtocol
		}
		result.CompletedAt = *completed
		value.Result = result
	}
	return value, nil
}

func (repo *Repository) Run(ctx context.Context, task string) (aidomain.Run, error) {
	return readRun(ctx, repo.database.Reader, task)
}

func checkRun(ctx context.Context, tx *sql.Tx, task string) (aidomain.Run, error) {
	run, err := readRun(ctx, tx, task)
	if err != nil {
		return run, err
	}
	if run.Result != nil {
		return run, aidomain.ErrSource
	}
	current, err := snapshotInTx(ctx, tx, run.Snapshot.ComicID, run.SampleCount)
	if err != nil {
		if errors.Is(err, aidomain.ErrNotFound) {
			return run, aidomain.ErrSource
		}
		return run, err
	}
	if !aidomain.SameSnapshot(run.Snapshot, current) {
		return run, aidomain.ErrSource
	}
	return run, nil
}

func (repo *Repository) Check(ctx context.Context, task string) error {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = checkRun(ctx, tx, task); err != nil {
		return err
	}
	return tx.Commit()
}

func (repo *Repository) Retry(task string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error { _, err := checkRun(ctx, tx, task); return err }
}

func (repo *Repository) Completion(task string, attempt int, request aidomain.Request, candidate aidomain.Candidate) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		if attempt < 1 || request.RequestID != aidomain.AnalysisRequestID(task, attempt) {
			return aidomain.ErrSource
		}
		run, err := checkRun(ctx, tx, task)
		if err != nil {
			return err
		}
		if err = aidomain.ValidatePrepared(run.Snapshot, request); err != nil {
			return err
		}
		if err = aidomain.ValidateCandidate(request, candidate); err != nil {
			return err
		}
		data, err := json.Marshal(request)
		if err != nil || len(data) > aidomain.MaxManifestBytes {
			return aidomain.ErrValidation
		}
		result, err := json.Marshal(candidate)
		if err != nil || len(result) > aidomain.MaxResponseBytes {
			return aidomain.ErrProtocol
		}
		updated, err := tx.ExecContext(ctx, `UPDATE ai_runs SET result_request_json=?,candidate_json=?,task_attempt=?,completed_at=? WHERE task_id=? AND candidate_json IS NULL AND EXISTS(SELECT 1 FROM tasks t WHERE t.id=ai_runs.task_id AND t.type='ai_analysis' AND t.status='running' AND t.attempt=?)`, string(data), string(result), attempt, time.Now().UTC().Format(jobsdomain.TimestampFormat), task, attempt)
		if err != nil {
			return err
		}
		count, err := updated.RowsAffected()
		if err != nil {
			return err
		}
		if count != 1 {
			return aidomain.ErrSource
		}
		return nil
	}
}
