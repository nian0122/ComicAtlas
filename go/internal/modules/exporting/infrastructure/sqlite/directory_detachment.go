package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
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
		// 按位置流式重算逐项报告，不能只凭行数或检查点阶段删除漫画。
		rows, err := tx.QueryContext(ctx, "SELECT "+directoryResultColumns+" FROM directory_move_results WHERE task_id=? ORDER BY position", run.TaskID)
		if err != nil {
			return err
		}
		hash := sha256.New()
		encoder := json.NewEncoder(hash)
		count := 0
		var bytes int64
		for rows.Next() {
			item, err := scanDirectoryResult(rows)
			if err != nil {
				rows.Close()
				return err
			}
			count++
			if exportdomain.ValidateDirectoryCompletedResult(item, count) != nil {
				rows.Close()
				return exportdomain.ErrManifest
			}
			bytes += item.Size
			if err = encoder.Encode(item); err != nil {
				rows.Close()
				return err
			}
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		if count != seal.FileCount || bytes != seal.ObservedBytes || hex.EncodeToString(hash.Sum(nil)) != report.ResultSHA256 {
			return exportdomain.ErrManifest
		}
		facts, err := guardDirectoryRun(ctx, tx, run)
		if err != nil {
			return err
		}
		now := time.Now().UTC().Format(directoryConfirmationTime)
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
