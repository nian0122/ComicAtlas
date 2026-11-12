-- 占用统计定时器的执行记录；只保留近期记录，旧记录在写入时裁剪。
CREATE TABLE storage_metrics_runs (
 id TEXT PRIMARY KEY NOT NULL,
 trigger TEXT NOT NULL CHECK(trigger IN ('startup','schedule','manual','auto')),
 started_at TEXT NOT NULL,
 finished_at TEXT,
 refreshed INTEGER NOT NULL DEFAULT 0 CHECK(refreshed>=0),
 error TEXT CHECK(error IS NULL OR length(error)>0),
 CHECK(finished_at IS NULL OR finished_at>=started_at)
);
CREATE INDEX storage_metrics_runs_recent ON storage_metrics_runs(started_at DESC, id DESC);
