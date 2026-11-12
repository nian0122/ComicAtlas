package sqlite

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
	"time"
)

var _ exportports.DirectoryConfirmationRepository = (*repository)(nil)

const directoryConfirmationTime = "2006-01-02T15:04:05.000000000Z"

func (repo *repository) SaveDirectoryPreview(ctx context.Context, record exportdomain.DirectoryPreviewRecord) error {
	write, err := directoryPreviewWrite(record, "")
	if err != nil {
		return err
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = write(ctx, tx); err != nil {
		return err
	}
	return tx.Commit()
}

func directoryPreviewWrite(record exportdomain.DirectoryPreviewRecord, exclude string) (persistence.WriteAction, error) {
	if record.TaskID != "" || exportdomain.CheckDirectoryConfirmation(record, record.TokenHash, time.Now().UTC(), false) != nil {
		return nil, exportdomain.ErrValidation
	}
	planning, err := exportdomain.DecodeDirectoryPlanning(record.Data, record.SHA256)
	if err != nil || planning.Seal.TaskID != record.ID {
		return nil, exportdomain.ErrManifest
	}
	scopeData, scopeHash, err := exportdomain.EncodeDirectoryScope(exportdomain.DirectoryAdmissionScope{PreviewID: record.ID, PlanSHA256: record.SHA256, Seal: planning.Seal, Inventories: planning.Inventories})
	if err != nil {
		return nil, err
	}
	books, files, bytes := len(planning.Inventories), planning.Seal.FileCount, planning.Seal.ObservedBytes
	return func(ctx context.Context, tx *sql.Tx) error {
		var err error
		accepted := record
		now := time.Now().UTC().Format(directoryConfirmationTime)
		if _, err = tx.ExecContext(ctx, "DELETE FROM directory_move_previews WHERE task_id IS NULL AND expires_at<=?", now); err != nil {
			return err
		}
		if err = directoryPreflightCapacity(ctx, tx, exclude); err != nil {
			return err
		}
		// 后台预检的有效期从结果实际入库时开始；同步内部测试入口保留指定时间校验。
		if exclude != "" {
			accepted.CreatedAt = time.Now().UTC()
			accepted.ExpiresAt = accepted.CreatedAt.Add(exportdomain.DirectoryPreviewTTL)
		}
		if _, err = tx.ExecContext(ctx, "INSERT INTO directory_move_previews(id,token_hash,plan_data,plan_sha,created_at,expires_at,scope_data,scope_sha,book_count,file_count,observed_bytes) VALUES(?,?,?,?,?,?,?,?,?,?,?)", accepted.ID, accepted.TokenHash, accepted.Data, accepted.SHA256, accepted.CreatedAt.UTC().Format(directoryConfirmationTime), accepted.ExpiresAt.UTC().Format(directoryConfirmationTime), scopeData, scopeHash, books, files, bytes); err != nil {
			return err
		}
		return nil
	}, nil
}

func (repo *repository) DirectoryPreview(ctx context.Context, id string) (exportdomain.DirectoryPreviewRecord, error) {
	return repo.readDirectoryPreview(ctx, id, true)
}

func (repo *repository) DirectoryConfirmationState(ctx context.Context, id string) (exportdomain.DirectoryPreviewRecord, error) {
	return repo.readDirectoryPreview(ctx, id, false)
}

func (repo *repository) readDirectoryPreview(ctx context.Context, id string, withPlan bool) (exportdomain.DirectoryPreviewRecord, error) {
	var record exportdomain.DirectoryPreviewRecord
	if !exportdomain.TaskID(id) {
		return record, exportdomain.ErrValidation
	}
	var created, expires string
	query := `SELECT id,token_hash,'',plan_sha,created_at,expires_at,COALESCE(task_id,'') FROM directory_move_previews WHERE id=?`
	args := []any{id}
	if withPlan {
		query = `SELECT id,token_hash,CASE WHEN length(CAST(plan_data AS BLOB))<=? THEN plan_data ELSE '' END,plan_sha,created_at,expires_at,COALESCE(task_id,'') FROM directory_move_previews WHERE id=?`
		args = []any{exportdomain.MaxDirectoryPreviewBytes, id}
	}
	err := repo.database.Reader.QueryRowContext(ctx, query, args...).Scan(&record.ID, &record.TokenHash, &record.Data, &record.SHA256, &created, &expires, &record.TaskID)
	if errors.Is(err, sql.ErrNoRows) {
		var pending bool
		if lookup := repo.database.Reader.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM directory_preflight_requests WHERE id=?)", id).Scan(&pending); lookup != nil {
			return record, lookup
		}
		if pending {
			return record, exportdomain.ErrConflict
		}
		return record, exportdomain.ErrNotFound
	}
	if err != nil {
		return record, err
	}
	if record.CreatedAt, err = time.Parse(directoryConfirmationTime, created); err != nil {
		return record, exportdomain.ErrManifest
	}
	if record.ExpiresAt, err = time.Parse(directoryConfirmationTime, expires); err != nil {
		return record, exportdomain.ErrManifest
	}
	return record, nil
}
