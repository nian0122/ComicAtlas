package sqlite

import (
	ingestionports "comicatlas.local/backend/internal/modules/ingestion/application/ports"
	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

type ScanRepository struct{ database *persistence.Database }

func NewScans(database *persistence.Database) *ScanRepository {
	return &ScanRepository{database: database}
}

var _ ingestionports.ScanRepository = (*ScanRepository)(nil)

func (repo *ScanRepository) Admission(root string) jobsports.Admission {
	return func(id string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			_, err := tx.ExecContext(ctx, "INSERT INTO import_scans(task_id,source_path) VALUES(?,?)", id, root)
			return err
		}
	}
}
func (repo *ScanRepository) Begin(ctx context.Context, id string) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "UPDATE import_scans SET complete=0 WHERE task_id=?", id); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM import_candidates WHERE task_id=?", id); err != nil {
		return err
	}
	return tx.Commit()
}
func (repo *ScanRepository) Save(ctx context.Context, id string, position int, item ingestiondomain.Candidate) error {
	data, err := json.Marshal(item)
	if err != nil {
		return err
	}
	_, err = repo.database.Writer.ExecContext(ctx, "INSERT INTO import_candidates VALUES(?,?,?,?,?,?,?)", id, item.ID, position, item.SourcePath, item.Kind, item.Selectable, string(data))
	return err
}
func (repo *ScanRepository) Completion(id string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, "UPDATE import_scans SET complete=1 WHERE task_id=?", id)
		return err
	}
}
func scanReady(ctx context.Context, tx *sql.Tx, id string) error {
	var complete bool
	var status string
	err := tx.QueryRowContext(ctx, "SELECT s.complete,t.status FROM import_scans s JOIN tasks t ON t.id=s.task_id WHERE s.task_id=?", id).Scan(&complete, &status)
	if errors.Is(err, sql.ErrNoRows) {
		return jobsdomain.ErrNotFound
	}
	if err != nil {
		return err
	}
	if !ingestiondomain.ScanReady(complete, status) {
		return jobsdomain.ErrConflict
	}
	return nil
}
func (repo *ScanRepository) List(ctx context.Context, id string, page, size int) (ingestiondomain.CandidatePage, error) {
	result := ingestiondomain.CandidatePage{Items: []ingestiondomain.Candidate{}, Page: page, PageSize: size}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	if err = scanReady(ctx, tx, id); err != nil {
		return result, err
	}
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM import_candidates WHERE task_id=?", id).Scan(&result.Total); err != nil {
		return result, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT candidate_json FROM import_candidates WHERE task_id=? ORDER BY position LIMIT ? OFFSET ?", id, size, (page-1)*size)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var data string
		var item ingestiondomain.Candidate
		if err = rows.Scan(&data); err != nil {
			rows.Close()
			return result, err
		}
		if err = json.Unmarshal([]byte(data), &item); err != nil {
			rows.Close()
			return result, err
		}
		result.Items = append(result.Items, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	return result, tx.Commit()
}
func selected(ctx context.Context, tx *sql.Tx, id string, ids []string) ([]ingestiondomain.Candidate, error) {
	if err := scanReady(ctx, tx, id); err != nil {
		return nil, err
	}
	items := make([]ingestiondomain.Candidate, 0, len(ids))
	for _, candidateID := range ids {
		var item ingestiondomain.Candidate
		err := tx.QueryRowContext(ctx, "SELECT id,source_path,kind,selectable FROM import_candidates WHERE task_id=? AND id=?", id, candidateID).Scan(&item.ID, &item.SourcePath, &item.Kind, &item.Selectable)
		if errors.Is(err, sql.ErrNoRows) {
			return nil, jobsdomain.ErrNotFound
		}
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, nil
}
func (repo *ScanRepository) Selection(ctx context.Context, id string, ids []string) ([]ingestiondomain.Candidate, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	items, err := selected(ctx, tx, id, ids)
	if err != nil {
		return nil, err
	}
	return items, tx.Commit()
}
func (repo *ScanRepository) SelectionAdmission(id string, items []ingestiondomain.Candidate) jobsports.BatchAdmission {
	return func([]string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			ids := make([]string, 0, len(items))
			for _, item := range items {
				ids = append(ids, item.ID)
			}
			current, err := selected(ctx, tx, id, ids)
			if err != nil {
				return err
			}
			if err = ingestiondomain.ValidateSelection(current); err != nil {
				return err
			}
			for index, item := range current {
				if item.SourcePath != items[index].SourcePath || item.Kind != items[index].Kind {
					return jobsdomain.ErrConflict
				}
			}
			return nil
		}
	}
}
