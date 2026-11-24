package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"time"
)

func (repo *repository) CompleteDirectoryRun(run exportdomain.DirectoryTaskRun, proof *exportdomain.DirectoryReport) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		if proof == nil {
			return exportdomain.ErrManifest
		}
		report := *proof
		seal := run.Planning.Seal
		if report.FileCount != seal.FileCount || report.Bytes != seal.ObservedBytes {
			return exportdomain.ErrManifest
		}
		var manifest string
		if err := tx.QueryRowContext(ctx, "SELECT t.manifest FROM directory_move_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND r.completed=0", run.TaskID).Scan(&manifest); err != nil {
			return err
		}
		if manifest != directoryRunMarker(run) {
			return exportdomain.ErrManifest
		}
		// 结果行在写入时已校验位置和状态，并且不可改写；收尾只核对总数、字节和状态。
		var count int
		var bytes int64
		var valid int
		if err := tx.QueryRowContext(ctx, `SELECT COUNT(*),COALESCE(SUM(size),0),
 COALESCE(SUM(CASE WHEN state IN('moved','initially_missing') THEN 1 ELSE 0 END),0)
 FROM directory_move_results WHERE task_id=?`, run.TaskID).Scan(&count, &bytes, &valid); err != nil {
			return err
		}
		if count != seal.FileCount || bytes != seal.ObservedBytes || valid != count || report.FileCount != count || report.Bytes != bytes {
			return exportdomain.ErrManifest
		}
		facts, err := guardDirectoryRun(ctx, tx, run)
		if err != nil {
			return err
		}
		now := time.Now().UTC().Format(directoryMoveTime)
		for _, entry := range facts.TrashIDs {
			if _, err = tx.ExecContext(ctx, "INSERT INTO directory_move_entries(task_id,entry_id) VALUES(?,?)", run.TaskID, entry); err != nil {
				return err
			}
			result, err := tx.ExecContext(ctx, "UPDATE lifecycle_entries SET state='detached',updated_at=? WHERE id=? AND state='trashed'", now, entry)
			if err != nil {
				return err
			}
			count, err := result.RowsAffected()
			if err != nil {
				return err
			}
			if count != 1 {
				return exportdomain.ErrConflict
			}
			if _, err = tx.ExecContext(ctx, "DELETE FROM lifecycle_cover_sources WHERE entry_id=?", entry); err != nil {
				return err
			}
		}
		for _, identity := range run.Planning.Inventories {
			result, err := tx.ExecContext(ctx, "DELETE FROM comics WHERE id=? AND lifecycle_state='moving_out'", identity.ComicID)
			if err != nil {
				return err
			}
			count, err := result.RowsAffected()
			if err != nil {
				return err
			}
			if count != 1 {
				return exportdomain.ErrConflict
			}
		}
		if _, err = tx.ExecContext(ctx, "UPDATE directory_move_members SET detached=1 WHERE task_id=? AND detached=0", run.TaskID); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE directory_move_runs SET completed=1,report_json=? WHERE task_id=? AND completed=0", directoryReportJSON(report), run.TaskID)
		return err
	}
}

func directoryReportJSON(report exportdomain.DirectoryReport) string {
	data, _ := json.Marshal(report)
	return string(data)
}
