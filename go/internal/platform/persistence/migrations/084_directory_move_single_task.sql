-- 单任务移出：计划由移出任务在执行前冻结，不再提供异步预检、确认令牌和五分钟窗口。
CREATE TABLE directory_move_upgrade_guard(unfinished INTEGER CHECK(unfinished=0));
INSERT INTO directory_move_upgrade_guard SELECT COUNT(*) FROM directory_move_runs WHERE completed=0;
DROP TABLE directory_move_upgrade_guard;

-- 旧预检只读且不再有执行器；取消活动任务，避免升级后留下无法执行的任务。
UPDATE tasks SET status='cancelled', finished_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
 WHERE type='directory_preflight' AND status IN('queued','running','cancelling');

-- 未受理预览没有计划消费者，直接删除。
DELETE FROM directory_move_previews WHERE task_id IS NULL;

-- 预览表重建为计划表，删除令牌、有效期、范围投影和任务占位列。
ALTER TABLE directory_move_previews RENAME TO directory_move_plans_old;
CREATE TABLE directory_move_plans (
 id TEXT PRIMARY KEY NOT NULL,
 plan_data TEXT NOT NULL CHECK(length(CAST(plan_data AS BLOB)) BETWEEN 1 AND 67108864),
 plan_sha TEXT NOT NULL CHECK(length(plan_sha)=64),
 created_at TEXT NOT NULL,
 book_count INTEGER CHECK(book_count BETWEEN 1 AND 10000),
 file_count INTEGER CHECK(file_count BETWEEN 0 AND 100000),
 observed_bytes INTEGER CHECK(observed_bytes BETWEEN 0 AND 107374182400)
);
INSERT INTO directory_move_plans(id,plan_data,plan_sha,created_at,book_count,file_count,observed_bytes)
 SELECT id,plan_data,plan_sha,created_at,book_count,file_count,observed_bytes FROM directory_move_plans_old;
CREATE INDEX directory_move_plan_created ON directory_move_plans(created_at);
CREATE TRIGGER directory_move_plan_immutable BEFORE UPDATE OF id,plan_data,plan_sha,created_at ON directory_move_plans
BEGIN SELECT RAISE(ABORT,'移出计划已冻结'); END;

-- 运行记录改挂计划；重建以移除旧预览外键并保留完成历史。
CREATE TABLE directory_move_runs_next (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id),
 plan_id TEXT NOT NULL UNIQUE REFERENCES directory_move_plans(id),
 completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN(0,1)),
 report_json TEXT CHECK(report_json IS NULL OR json_valid(report_json))
);
INSERT INTO directory_move_runs_next(task_id,plan_id,completed,report_json)
 SELECT task_id,preview_id,completed,report_json FROM directory_move_runs;
DROP TABLE directory_move_runs;
ALTER TABLE directory_move_runs_next RENAME TO directory_move_runs;
CREATE TRIGGER directory_move_run_immutable BEFORE UPDATE OF task_id,plan_id ON directory_move_runs
BEGIN SELECT RAISE(ABORT,'移出任务身份已冻结'); END;
CREATE TRIGGER directory_move_completion_monotonic BEFORE UPDATE OF completed ON directory_move_runs WHEN OLD.completed=1 AND NEW.completed<>1
BEGIN SELECT RAISE(ABORT,'移出已完成'); END;

DROP TABLE directory_move_plans_old;

-- 请求表改为单任务受理范围，移除确认凭据。
DROP TRIGGER IF EXISTS directory_preflight_request_immutable;
ALTER TABLE directory_preflight_requests RENAME TO directory_move_requests;
ALTER TABLE directory_move_requests DROP COLUMN token_hash;
CREATE TRIGGER directory_move_request_immutable BEFORE UPDATE ON directory_move_requests
BEGIN SELECT RAISE(ABORT,'移出请求已冻结'); END;
