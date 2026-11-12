package sqlite

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

var _ exportports.DirectoryPreflightRepository = (*repository)(nil)

func directoryPreflightCapacity(ctx context.Context, tx *sql.Tx, exclude string) error {
	var active, ready int
	err := tx.QueryRowContext(ctx, `SELECT
 (SELECT COUNT(*) FROM tasks WHERE type='directory_preflight' AND id<>? AND status IN('queued','running','cancelling')),
 (SELECT COUNT(*) FROM directory_move_previews WHERE task_id IS NULL AND expires_at>?)`, exclude, time.Now().UTC().Format(directoryConfirmationTime)).Scan(&active, &ready)
	if err != nil {
		return err
	}
	return exportdomain.DirectoryPreflightCapacity(active, ready)
}

func (repo *repository) AdmitDirectoryPreflight(request exportdomain.DirectoryPreflightRequest) jobsports.Admission {
	inputErr := exportdomain.ValidateDirectoryPreflight(request)
	var data []byte
	if inputErr == nil {
		data, inputErr = json.Marshal(request.ComicIDs)
	}
	return func(task string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			if inputErr != nil {
				return inputErr
			}
			if err := directoryPreflightCapacity(ctx, tx, task); err != nil {
				return err
			}
			_, err := tx.ExecContext(ctx, `INSERT INTO directory_preflight_requests(id,task_id,comic_ids,book_count,token_hash,created_at) VALUES(?,?,?,?,?,?)`, request.ID, task, string(data), len(request.ComicIDs), request.TokenHash, request.CreatedAt.UTC().Format(directoryConfirmationTime))
			return err
		}
	}
}

func (repo *repository) LoadDirectoryPreflight(ctx context.Context, task, id string) (exportdomain.DirectoryPreflightRequest, error) {
	var request exportdomain.DirectoryPreflightRequest
	var data, created string
	var count int
	err := repo.database.Reader.QueryRowContext(ctx, `SELECT r.id,r.token_hash,CASE WHEN length(CAST(r.comic_ids AS BLOB))<=2097152 THEN r.comic_ids ELSE '' END,r.book_count,r.created_at
 FROM directory_preflight_requests r JOIN tasks t ON t.id=r.task_id AND t.type='directory_preflight' AND t.target_type='directory_preview' AND t.target_id=r.id AND t.comic_id='' AND t.request_json='{}'
 WHERE r.id=? AND r.task_id=? AND t.status='running'`, id, task).Scan(&request.ID, &request.TokenHash, &data, &count, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return request, exportdomain.ErrConflict
	}
	if err != nil {
		return request, err
	}
	// 声明数量在分配前检查，逐项读取并拒绝超量内容。
	request.ComicIDs, err = exportdomain.DecodeDirectoryPreflightIDs(data, count)
	if err != nil {
		return request, exportdomain.ErrManifest
	}
	request.CreatedAt, err = time.Parse(directoryConfirmationTime, created)
	if err != nil {
		return request, exportdomain.ErrManifest
	}
	if exportdomain.ValidateDirectoryPreflight(request) != nil {
		return request, exportdomain.ErrManifest
	}
	return request, nil
}

func (repo *repository) DirectoryPreflightStatus(ctx context.Context, id string) (exportdomain.DirectoryPreflightView, error) {
	var view exportdomain.DirectoryPreflightView
	var expires sql.NullString
	var fileCount, bytes sql.NullInt64
	err := repo.database.Reader.QueryRowContext(ctx, `SELECT r.id,r.task_id,t.status,r.book_count,p.expires_at,p.file_count,p.observed_bytes,COALESCE(p.task_id,'')
 FROM directory_preflight_requests r JOIN tasks t ON t.id=r.task_id AND t.type='directory_preflight' AND t.target_type='directory_preview' AND t.target_id=r.id
 LEFT JOIN directory_move_previews p ON p.id=r.id WHERE r.id=?`, id).Scan(&view.ID, &view.TaskID, &view.Status, &view.BookCount, &expires, &fileCount, &bytes, &view.MoveTaskID)
	if errors.Is(err, sql.ErrNoRows) {
		return view, exportdomain.ErrNotFound
	}
	if err != nil {
		return view, err
	}
	if expires.Valid {
		value, err := time.Parse(directoryConfirmationTime, expires.String)
		if err != nil || !fileCount.Valid || !bytes.Valid {
			return view, exportdomain.ErrManifest
		}
		view.ExpiresAt = &value
		files := int(fileCount.Int64)
		view.FileCount = &files
		view.Bytes = &bytes.Int64
	}
	return view, nil
}

func (repo *repository) CompleteDirectoryPreflight(ctx context.Context, task string, record exportdomain.DirectoryPreviewRecord) (persistence.WriteAction, error) {
	write, err := directoryPreviewWrite(record, task)
	if err != nil {
		return nil, err
	}
	return func(ctx context.Context, tx *sql.Tx) error {
		var valid bool
		if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM directory_preflight_requests r JOIN tasks t ON t.id=r.task_id WHERE r.id=? AND r.task_id=? AND r.token_hash=? AND t.type='directory_preflight' AND t.target_id=r.id AND t.target_type='directory_preview' AND t.comic_id='' AND t.status='running')`, record.ID, task, record.TokenHash).Scan(&valid); err != nil {
			return err
		}
		if !valid {
			return exportdomain.ErrConflict
		}
		return write(ctx, tx)
	}, nil
}

func (repo *repository) RetryDirectoryPreflight(task string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		var valid bool
		if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM directory_preflight_requests r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND t.type='directory_preflight' AND t.status='failed' AND t.target_type='directory_preview' AND t.target_id=r.id AND NOT EXISTS(SELECT 1 FROM directory_move_previews p WHERE p.id=r.id))`, task).Scan(&valid); err != nil {
			return err
		}
		if !valid {
			return exportdomain.ErrConflict
		}
		return directoryPreflightCapacity(ctx, tx, task)
	}
}
