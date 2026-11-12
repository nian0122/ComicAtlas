package sqlite

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
)

func readRepairFacts(ctx context.Context, query rowReader, comic, exclude string) (facts recoverydomain.RepairFacts, err error) {
	err = query.QueryRowContext(ctx, "SELECT c.version,c.structure_version,p.created_at,p.task_id FROM active_comics c JOIN comic_recovery_placeholders p ON p.comic_id=c.id WHERE c.id=?", comic).Scan(&facts.Version, &facts.StructureVersion, &facts.MarkerCreatedAt, &facts.MarkerTaskID)
	if errors.Is(err, sql.ErrNoRows) {
		err = recoverydomain.ErrMissing
	}
	if err != nil {
		return facts, err
	}
	// 即使隐藏章节也不允许把已有结构覆盖成补全来源；只投影主键范围存在性。
	if err = query.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM chapters WHERE comic_id=?) OR EXISTS(SELECT 1 FROM catalogs WHERE comic_id=?)", comic, comic).Scan(&facts.HasStructure); err != nil {
		return facts, err
	}
	err = query.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM tasks WHERE comic_id=? AND id<>? AND status IN('queued','running','cancelling'))
 OR EXISTS(SELECT 1 FROM lifecycle_runs WHERE comic_id=? AND completed=0)
 OR EXISTS(SELECT 1 FROM directory_move_members WHERE comic_id=? AND detached=0)
 OR EXISTS(SELECT 1 FROM upload_sessions WHERE comic_id=? AND status IN('active','submitted','activated'))`, comic, exclude, comic, comic, comic).Scan(&facts.Busy)
	return facts, err
}

func (repo *sqliteRepository) RepairAdmission(comic string, request recoverydomain.RepairRequest) jobsports.Admission {
	return func(task string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			if err := recoverydomain.ValidateRepairRequest(comic, request); err != nil {
				return err
			}
			facts, err := readRepairFacts(ctx, tx, comic, task)
			if err != nil {
				return err
			}
			if err = recoverydomain.ValidateRepairFacts(request, facts); err != nil {
				return err
			}
			result, err := tx.ExecContext(ctx, `INSERT INTO recovery_repairs(task_id,comic_id,expected_version,expected_structure_version,marker_created_at,marker_task_id) SELECT id,comic_id,?,?,?,? FROM tasks WHERE id=? AND type='recovery_repair' AND comic_id=? AND target_id=? AND status='queued'`, request.Version, request.StructureVersion, facts.MarkerCreatedAt, facts.MarkerTaskID, task, comic, comic)
			if err != nil {
				return err
			}
			count, err := result.RowsAffected()
			if err != nil {
				return err
			}
			if count != 1 {
				return recoverydomain.ErrConflict
			}
			return nil
		}
	}
}

func (repo *sqliteRepository) RepairRun(ctx context.Context, task string) (run recoverydomain.RepairRun, err error) {
	return readRepairRun(ctx, repo.database.Reader, task)
}

func readRepairRun(ctx context.Context, query rowReader, task string) (run recoverydomain.RepairRun, err error) {
	if !recoverydomain.Key(task) {
		return run, recoverydomain.ErrValidation
	}
	run.TaskID = task
	err = query.QueryRowContext(ctx, `SELECT r.comic_id,r.expected_version,r.expected_structure_version,r.marker_created_at,r.marker_task_id,r.completed,r.source_sha,r.completed_at,r.version,r.structure_version,r.preserved_metadata FROM recovery_repairs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND t.type='recovery_repair'`, task).Scan(&run.ComicID, &run.ExpectedVersion, &run.ExpectedStructureVersion, &run.MarkerCreatedAt, &run.MarkerTaskID, &run.Completed, &run.SourceSHA, &run.CompletedAt, &run.Version, &run.StructureVersion, &run.PreservedMetadata)
	if errors.Is(err, sql.ErrNoRows) {
		err = recoverydomain.ErrMissing
	}
	if err != nil {
		run = recoverydomain.RepairRun{}
	}
	return run, err
}

func (repo *sqliteRepository) RepairRetry(task string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		run, err := readRepairRun(ctx, tx, task)
		if err != nil {
			return err
		}
		facts, err := readRepairFacts(ctx, tx, run.ComicID, task)
		if errors.Is(err, recoverydomain.ErrMissing) {
			return recoverydomain.ErrConflict
		}
		if err != nil {
			return err
		}
		return recoverydomain.ValidateRepairGeneration(run, facts)
	}
}
