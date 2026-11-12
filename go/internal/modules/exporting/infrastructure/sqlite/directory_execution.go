package sqlite

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

var _ exportports.DirectoryExecutionRepository = (*repository)(nil)

func (repo *repository) LoadDirectoryRun(ctx context.Context, id string) (exportdomain.DirectoryTaskRun, error) {
	var run exportdomain.DirectoryTaskRun
	if !exportdomain.TaskID(id) {
		return run, exportdomain.ErrValidation
	}
	var data string
	err := repo.database.Reader.QueryRowContext(ctx, `SELECT r.task_id,r.preview_id,r.completed,p.plan_sha,
 CASE WHEN length(CAST(p.plan_data AS BLOB))<=? THEN p.plan_data ELSE '' END
 FROM directory_move_runs r JOIN directory_move_previews p ON p.id=r.preview_id AND p.task_id=r.task_id
 JOIN tasks t ON t.id=r.task_id AND t.type='directory_move' AND t.target_type='directory_preview' AND t.target_id=p.id AND t.comic_id='' WHERE r.task_id=?`, exportdomain.MaxDirectoryPreviewBytes, id).Scan(&run.TaskID, &run.PreviewID, &run.Completed, &run.SHA256, &data)
	if errors.Is(err, sql.ErrNoRows) {
		return run, exportdomain.ErrNotFound
	}
	if err != nil {
		return run, err
	}
	if run.Completed {
		return run, exportdomain.ErrConflict
	}
	run.Planning, err = exportdomain.DecodeDirectoryPlanning(data, run.SHA256)
	if err != nil || run.Planning.Seal.TaskID != run.PreviewID {
		return run, exportdomain.ErrManifest
	}
	return run, nil
}

func guardDirectoryRun(ctx context.Context, tx *sql.Tx, run exportdomain.DirectoryTaskRun) (exportdomain.DirectoryDetachFacts, error) {
	var facts exportdomain.DirectoryDetachFacts
	var valid bool
	if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM directory_move_runs r JOIN directory_move_previews p ON p.id=r.preview_id AND p.task_id=r.task_id
 JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND r.preview_id=? AND p.plan_sha=? AND r.completed=0 AND t.type='directory_move' AND t.target_type='directory_preview' AND t.target_id=r.preview_id AND t.comic_id='' AND t.status='running')`, run.TaskID, run.PreviewID, run.SHA256).Scan(&valid); err != nil {
		return facts, err
	}
	if !valid {
		return facts, exportdomain.ErrConflict
	}
	rows, err := tx.QueryContext(ctx, `SELECT m.position,m.comic_id,m.inventory_sha,m.detached,m.accepted_revision,
 COALESCE(v.revision,0),COALESCE(c.lifecycle_state,'') FROM directory_move_members m LEFT JOIN comics c ON c.id=m.comic_id
 LEFT JOIN comic_change_versions v ON v.comic_id=c.id WHERE m.task_id=? ORDER BY m.position`, run.TaskID)
	if err != nil {
		return facts, err
	}
	defer rows.Close()
	index := 0
	for rows.Next() {
		var position int
		var comic, stamp, state string
		var detached bool
		var accepted, current int64
		if err = rows.Scan(&position, &comic, &stamp, &detached, &accepted, &current, &state); err != nil {
			return facts, err
		}
		if index >= len(run.Planning.Inventories) {
			return facts, exportdomain.ErrManifest
		}
		expected := run.Planning.Inventories[index]
		if position != index+1 || comic != expected.ComicID || stamp != expected.SHA256 || detached {
			return facts, exportdomain.ErrManifest
		}
		if state != "moving_out" || accepted <= expected.MutationVersion || accepted != current {
			return facts, exportdomain.ErrConflict
		}
		index++
	}
	if err = rows.Err(); err != nil {
		return facts, err
	}
	if index == 0 || index != len(run.Planning.Inventories) {
		return facts, exportdomain.ErrManifest
	}
	return exportdomain.DirectoryDetachPlans(run.Planning.Plans), nil
}

func directoryRunMarker(run exportdomain.DirectoryTaskRun) string {
	data, _ := json.Marshal(exportdomain.DirectoryMarker{Version: exportdomain.DirectoryPlanVersion, TaskID: run.TaskID, PlanSHA256: run.SHA256})
	return string(data)
}

func (repo *repository) StartDirectoryRun(ctx context.Context, run exportdomain.DirectoryTaskRun) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = guardDirectoryRun(ctx, tx, run); err != nil {
		return err
	}
	marker := directoryRunMarker(run)
	result, err := tx.ExecContext(ctx, `UPDATE tasks SET manifest=?,stage='moving',completed_count=(SELECT COUNT(*) FROM directory_move_results WHERE task_id=tasks.id),total_count=? WHERE id=? AND type='directory_move' AND status='running' AND (manifest IS NULL OR manifest=?)`, marker, run.Planning.Seal.FileCount, run.TaskID, marker)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return exportdomain.ErrManifest
	}
	return tx.Commit()
}
