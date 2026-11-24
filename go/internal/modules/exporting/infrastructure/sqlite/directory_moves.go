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

var _ exportports.DirectoryMoveRepository = (*repository)(nil)

const directoryMoveTime = "2006-01-02T15:04:05.000000000Z"

func (repo *repository) AdmitDirectoryMove(request exportdomain.DirectoryMoveRequest) jobsports.Admission {
	inputErr := exportdomain.ValidateDirectoryMoveRequest(request)
	var data []byte
	if inputErr == nil {
		data, inputErr = json.Marshal(request.ComicIDs)
	}
	return func(task string) persistence.WriteAction {
		return func(ctx context.Context, tx *sql.Tx) error {
			if inputErr != nil {
				return inputErr
			}
			var active, locked int
			if err := tx.QueryRowContext(ctx, `SELECT
 (SELECT COUNT(*) FROM active_comics WHERE id IN(SELECT value FROM json_each(?))),
 (SELECT COUNT(*) FROM directory_move_members WHERE comic_id IN(SELECT value FROM json_each(?)) AND detached=0)`, string(data), string(data)).Scan(&active, &locked); err != nil {
				return err
			}
			if active != len(request.ComicIDs) || locked != 0 {
				return exportdomain.ErrConflict
			}
			_, err := tx.ExecContext(ctx, `INSERT INTO directory_move_requests(id,task_id,comic_ids,book_count,created_at) VALUES(?,?,?,?,?)`, request.ID, task, string(data), len(request.ComicIDs), request.CreatedAt.UTC().Format(directoryMoveTime))
			return err
		}
	}
}

func (repo *repository) LoadDirectoryMoveRequest(ctx context.Context, task, id string) (exportdomain.DirectoryMoveRequest, error) {
	var request exportdomain.DirectoryMoveRequest
	var data, created string
	var count int
	err := repo.database.Reader.QueryRowContext(ctx, `SELECT r.id,r.comic_ids,r.book_count,r.created_at
 FROM directory_move_requests r JOIN tasks t ON t.id=r.task_id AND t.type='directory_move' AND t.target_type='directory_preview' AND t.target_id=r.id AND t.comic_id=''
 WHERE r.id=? AND r.task_id=?`, id, task).Scan(&request.ID, &data, &count, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return request, exportdomain.ErrNotFound
	}
	if err != nil {
		return request, err
	}
	request.ComicIDs, err = exportdomain.DecodeDirectoryMoveIDs(data, count)
	if err != nil {
		return request, exportdomain.ErrManifest
	}
	request.CreatedAt, err = time.Parse(directoryMoveTime, created)
	if err != nil {
		return request, exportdomain.ErrManifest
	}
	if exportdomain.ValidateDirectoryMoveRequest(request) != nil {
		return request, exportdomain.ErrManifest
	}
	return request, nil
}

func (repo *repository) LoadDirectoryTaskRun(ctx context.Context, id string) (exportdomain.DirectoryTaskRun, error) {
	var run exportdomain.DirectoryTaskRun
	if !exportdomain.TaskID(id) {
		return run, exportdomain.ErrValidation
	}
	var data string
	err := repo.database.Reader.QueryRowContext(ctx, `SELECT r.task_id,r.plan_id,r.completed,
 CASE WHEN length(CAST(p.plan_data AS BLOB))<=? THEN p.plan_data ELSE '' END
 FROM directory_move_runs r JOIN directory_move_plans p ON p.id=r.plan_id
 JOIN tasks t ON t.id=r.task_id AND t.type='directory_move' AND t.target_type='directory_preview' AND t.target_id=p.id AND t.comic_id='' WHERE r.task_id=?`, exportdomain.MaxDirectoryPlanReadBytes, id).Scan(&run.TaskID, &run.PlanID, &run.Completed, &data)
	if errors.Is(err, sql.ErrNoRows) {
		return run, exportdomain.ErrNotFound
	}
	if err != nil {
		return run, err
	}
	if run.Completed {
		return run, exportdomain.ErrConflict
	}
	run.Planning, err = exportdomain.DecodeDirectoryPlanning(data)
	if err != nil || run.Planning.Seal.TaskID != run.PlanID {
		return run, exportdomain.ErrManifest
	}
	return run, nil
}

func directoryMoveIDs(inventories []exportdomain.DirectoryInventoryIdentity) (string, error) {
	ids := make([]string, 0, len(inventories))
	for _, identity := range inventories {
		ids = append(ids, identity.ComicID)
	}
	data, err := json.Marshal(ids)
	return string(data), err
}

func directoryMoveVersions(ctx context.Context, tx *sql.Tx, inventories []exportdomain.DirectoryInventoryIdentity) (string, error) {
	data, err := directoryMoveIDs(inventories)
	if err != nil {
		return "", err
	}
	rows, err := tx.QueryContext(ctx, `SELECT selected.value,COALESCE(v.revision,0) FROM json_each(?) selected
 LEFT JOIN active_comics c ON c.id=selected.value LEFT JOIN comic_change_versions v ON v.comic_id=c.id ORDER BY CAST(selected.key AS INTEGER)`, data)
	if err != nil {
		return "", err
	}
	current := make([]exportdomain.DirectoryCurrentVersion, 0, len(inventories))
	for rows.Next() {
		var item exportdomain.DirectoryCurrentVersion
		if err = rows.Scan(&item.ComicID, &item.MutationVersion); err != nil {
			rows.Close()
			return "", err
		}
		current = append(current, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return "", err
	}
	if err = exportdomain.ValidateDirectoryVersions(inventories, current); err != nil {
		return "", err
	}
	var activity exportdomain.DirectoryActivity
	err = tx.QueryRowContext(ctx, `SELECT
 (SELECT COUNT(*) FROM tasks WHERE comic_id IN(SELECT value FROM json_each(:ids)) AND status IN('queued','running','cancelling')),
 (SELECT COUNT(*) FROM upload_sessions WHERE comic_id IN(SELECT value FROM json_each(:ids)) AND status IN('active','submitted','activated')),
 (SELECT COUNT(*) FROM lifecycle_runs WHERE comic_id IN(SELECT value FROM json_each(:ids)) AND completed=0),
 (SELECT COUNT(*) FROM metadata_refresh_runs WHERE comic_id IN(SELECT value FROM json_each(:ids)) AND applied=1 AND completed=0),
 (SELECT COUNT(*) FROM video_transcode_items i JOIN tasks t ON t.id=i.task_id WHERE t.comic_id IN(SELECT value FROM json_each(:ids)) AND i.status='prepared'),
 (SELECT COUNT(*) FROM upload_retired r JOIN upload_sessions s ON s.task_id=r.task_id WHERE s.comic_id IN(SELECT value FROM json_each(:ids)) AND r.status='pending')`, sql.Named("ids", data)).Scan(&activity.Tasks, &activity.Uploads, &activity.LifecycleRuns, &activity.RefreshRuns, &activity.PreparedTranscodes, &activity.PendingRetired)
	if err != nil {
		return "", err
	}
	if err = exportdomain.DirectoryInactive(activity); err != nil {
		return "", err
	}
	return data, nil
}

// SaveDirectoryMovePlan 在同一事务内冻结计划、隐藏范围并创建执行记录。
func (repo *repository) SaveDirectoryMovePlan(ctx context.Context, task string, request exportdomain.DirectoryMoveRequest, planning exportdomain.DirectoryPlanningResult) (exportdomain.DirectoryTaskRun, error) {
	var run exportdomain.DirectoryTaskRun
	if err := exportdomain.ValidateDirectoryPlanning(planning); err != nil || planning.Seal.TaskID != request.ID {
		return run, exportdomain.ErrManifest
	}
	data, err := exportdomain.EncodeDirectoryPlanning(planning)
	if err != nil {
		return run, err
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return run, err
	}
	defer tx.Rollback()
	var valid bool
	if err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM tasks WHERE id=? AND type='directory_move' AND status='running' AND target_type='directory_preview' AND target_id=? AND comic_id='' AND request_json='{}')`, task, request.ID).Scan(&valid); err != nil {
		return run, err
	}
	if !valid {
		return run, exportdomain.ErrConflict
	}
	if err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM directory_move_requests WHERE id=? AND task_id=? AND book_count=?)`, request.ID, task, len(request.ComicIDs)).Scan(&valid); err != nil {
		return run, err
	}
	if !valid {
		return run, exportdomain.ErrManifest
	}
	ids, err := directoryMoveVersions(ctx, tx, planning.Inventories)
	if err != nil {
		return run, err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO directory_move_plans(id,plan_data,created_at,book_count,file_count,observed_bytes) VALUES(?,?,?,?,?,?)`, request.ID, data, time.Now().UTC().Format(directoryMoveTime), len(planning.Inventories), planning.Seal.FileCount, planning.Seal.ObservedBytes); err != nil {
		return run, err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO directory_move_runs(task_id,plan_id) VALUES(?,?)`, task, request.ID); err != nil {
		return run, err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO directory_move_term_locks(task_id,kind,term_id) SELECT DISTINCT ?,'categories',c.category_id FROM active_comics c WHERE c.id IN(SELECT value FROM json_each(?)) AND c.category_id IS NOT NULL`, task, ids); err != nil {
		return run, err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO directory_move_term_locks(task_id,kind,term_id) SELECT DISTINCT ?,'tags',ct.tag_id FROM comic_tags ct JOIN active_comics c ON c.id=ct.comic_id WHERE c.id IN(SELECT value FROM json_each(?))`, task, ids); err != nil {
		return run, err
	}
	for index, identity := range planning.Inventories {
		result, err := tx.ExecContext(ctx, "UPDATE comics SET lifecycle_state='moving_out' WHERE id=? AND lifecycle_state='active'", identity.ComicID)
		if err != nil {
			return run, err
		}
		count, err := result.RowsAffected()
		if err != nil {
			return run, err
		}
		if count != 1 {
			return run, exportdomain.ErrConflict
		}
		var accepted int64
		if err = tx.QueryRowContext(ctx, "SELECT revision FROM comic_change_versions WHERE comic_id=?", identity.ComicID).Scan(&accepted); err != nil {
			return run, err
		}
		if _, err = tx.ExecContext(ctx, "INSERT INTO directory_move_members(task_id,position,comic_id,accepted_revision) VALUES(?,?,?,?)", task, index+1, identity.ComicID, accepted); err != nil {
			return run, err
		}
	}
	if err = tx.Commit(); err != nil {
		return run, err
	}
	run = exportdomain.DirectoryTaskRun{TaskID: task, PlanID: request.ID, Planning: planning}
	return run, nil
}

func (repo *repository) GuardDirectoryRetry(id string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		if !exportdomain.TaskID(id) {
			return exportdomain.ErrValidation
		}
		var state exportdomain.DirectoryRetryState
		err := tx.QueryRowContext(ctx, `SELECT t.id,t.type,t.status,t.comic_id,t.target_type,t.target_id,COALESCE(t.request_json,''),
 COALESCE(r.plan_id,''),COALESCE(r.completed,0),
 (SELECT COUNT(*) FROM directory_move_members WHERE task_id=t.id),
 (SELECT COUNT(*) FROM directory_move_members WHERE task_id=t.id AND detached<>0),
 EXISTS(SELECT 1 FROM directory_move_plans WHERE id=t.target_id)
 FROM tasks t LEFT JOIN directory_move_runs r ON r.task_id=t.id LEFT JOIN directory_move_plans p ON p.id=r.plan_id WHERE t.id=?`, id).Scan(&state.TaskID, &state.Type, &state.Status, &state.ComicID, &state.TargetType, &state.TargetID, &state.Payload, &state.PlanID, &state.Completed, &state.Members, &state.DetachedMembers, &state.HasPlan)
		if errors.Is(err, sql.ErrNoRows) {
			return exportdomain.ErrNotFound
		}
		if err != nil {
			return err
		}
		return exportdomain.ValidateDirectoryRetry(id, state)
	}
}

func guardDirectoryRun(ctx context.Context, tx *sql.Tx, run exportdomain.DirectoryTaskRun) (exportdomain.DirectoryDetachFacts, error) {
	var facts exportdomain.DirectoryDetachFacts
	var valid bool
	if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM directory_move_runs r JOIN directory_move_plans p ON p.id=r.plan_id
 JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND r.plan_id=? AND r.completed=0 AND t.type='directory_move' AND t.target_type='directory_preview' AND t.target_id=r.plan_id AND t.comic_id='' AND t.status='running')`, run.TaskID, run.PlanID).Scan(&valid); err != nil {
		return facts, err
	}
	if !valid {
		return facts, exportdomain.ErrConflict
	}
	rows, err := tx.QueryContext(ctx, `SELECT m.position,m.comic_id,m.detached,m.accepted_revision,
 COALESCE(v.revision,0),COALESCE(c.lifecycle_state,'') FROM directory_move_members m LEFT JOIN comics c ON c.id=m.comic_id
 LEFT JOIN comic_change_versions v ON v.comic_id=c.id WHERE m.task_id=? ORDER BY m.position`, run.TaskID)
	if err != nil {
		return facts, err
	}
	defer rows.Close()
	index := 0
	for rows.Next() {
		var position int
		var comic, state string
		var detached bool
		var accepted, current int64
		if err = rows.Scan(&position, &comic, &detached, &accepted, &current, &state); err != nil {
			return facts, err
		}
		if index >= len(run.Planning.Inventories) {
			return facts, exportdomain.ErrManifest
		}
		expected := run.Planning.Inventories[index]
		if position != index+1 || comic != expected.ComicID || detached {
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
	data, _ := json.Marshal(exportdomain.DirectoryMarker{Version: exportdomain.DirectoryPlanVersion, TaskID: run.TaskID})
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
