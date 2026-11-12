-- 通用定时器：设置与执行日志从存储专用表迁移为按定时器 ID 管理。
CREATE TABLE scheduler_settings (
 schedule_id TEXT PRIMARY KEY NOT NULL,
 enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
 interval_seconds INTEGER NOT NULL CHECK(interval_seconds BETWEEN 60 AND 604800),
 updated_at TEXT NOT NULL
);
CREATE TABLE scheduler_runs (
 id TEXT PRIMARY KEY NOT NULL,
 schedule_id TEXT NOT NULL,
 trigger TEXT NOT NULL CHECK(trigger IN ('startup','schedule','manual','auto')),
 started_at TEXT NOT NULL,
 finished_at TEXT,
 processed INTEGER NOT NULL DEFAULT 0 CHECK(processed>=0),
 error TEXT CHECK(error IS NULL OR length(error)>0),
 CHECK(finished_at IS NULL OR finished_at>=started_at)
);
CREATE INDEX scheduler_runs_recent ON scheduler_runs(schedule_id, started_at DESC, id DESC);
INSERT INTO scheduler_runs(id,schedule_id,trigger,started_at,finished_at,processed,error)
 SELECT id,'storage-metrics',trigger,started_at,finished_at,refreshed,error FROM storage_metrics_runs;
DROP TABLE storage_metrics_runs;
