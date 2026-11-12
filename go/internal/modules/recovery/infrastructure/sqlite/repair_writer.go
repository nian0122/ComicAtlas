package sqlite

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
	"github.com/mattn/go-sqlite3"
)

func (repo *sqliteRepository) RepairWriter(run recoverydomain.RepairRun, sidecar recoverydomain.Sidecar, attempt int, hash, recorded string) persistence.WriteAction {
	proofErr := recoverydomain.ValidateRepairProof(run, attempt, hash)
	plan, prepareErr := recoverydomain.PrepareRestore(run.ComicID, sidecar, run.TaskID, recorded)
	policy, policyErr := recoverydomain.RepairVersionPolicy(recoverydomain.RepairRequest{Version: run.ExpectedVersion, StructureVersion: run.ExpectedStructureVersion}, plan.Snapshot.Detail.Version, plan.Snapshot.Detail.StructureVersion)
	if run.MarkerTaskID != nil {
		copy := *run.MarkerTaskID
		run.MarkerTaskID = &copy
	}
	return func(ctx context.Context, tx *sql.Tx) (err error) {
		defer func() {
			var constraint sqlite3.Error
			if errors.As(err, &constraint) && constraint.Code == sqlite3.ErrConstraint {
				err = errors.Join(recoverydomain.ErrConflict, err)
			}
		}()
		if proofErr != nil {
			return proofErr
		}
		if prepareErr != nil {
			return prepareErr
		}
		if policyErr != nil {
			return policyErr
		}
		var active bool
		if err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM tasks t JOIN recovery_repairs r ON r.task_id=t.id WHERE t.id=? AND t.type='recovery_repair' AND t.comic_id=? AND t.target_id=? AND t.status='running' AND t.attempt=? AND t.total_count=1 AND t.completed_count=0 AND r.completed=0 AND r.expected_version=? AND r.expected_structure_version=? AND r.marker_created_at=? AND r.marker_task_id IS ?)`, run.TaskID, run.ComicID, run.ComicID, attempt, run.ExpectedVersion, run.ExpectedStructureVersion, run.MarkerCreatedAt, run.MarkerTaskID).Scan(&active); err != nil {
			return err
		}
		if !active {
			return recoverydomain.ErrConflict
		}
		facts, err := readRepairFacts(ctx, tx, run.ComicID, run.TaskID)
		if err != nil {
			return err
		}
		if err = recoverydomain.ValidateRepairGeneration(run, facts); err != nil {
			return err
		}
		plan.Snapshot.Detail.Version, plan.Snapshot.Detail.StructureVersion = policy.Version, policy.StructureVersion
		if policy.PreserveMetadata {
			_, err = tx.ExecContext(ctx, "UPDATE comics SET version=?,structure_version=? WHERE id=?", policy.Version, policy.StructureVersion, run.ComicID)
		} else {
			err = restoreMetadata(ctx, tx, plan.Snapshot.Detail, false)
		}
		if err != nil {
			return err
		}
		if err = restoreStructure(ctx, tx, plan.Snapshot); err != nil {
			return err
		}
		if err = restoreMedia(ctx, tx, plan); err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, "DELETE FROM comic_recovery_placeholders WHERE comic_id=?", run.ComicID); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE recovery_repairs SET completed=1,source_sha=?,completed_at=?,version=?,structure_version=?,preserved_metadata=? WHERE task_id=?", hash, recorded, policy.Version, policy.StructureVersion, policy.PreserveMetadata, run.TaskID)
		return err
	}
}
