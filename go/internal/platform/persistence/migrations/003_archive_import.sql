ALTER TABLE comics ADD COLUMN author TEXT NOT NULL DEFAULT '';
ALTER TABLE comics ADD COLUMN description TEXT NOT NULL DEFAULT '';
CREATE TABLE tasks_new (
    id TEXT PRIMARY KEY NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('directory_import','archive_import')),
    source_path TEXT NOT NULL,
    title TEXT NOT NULL,
    comic_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('queued','running','cancelling','cancelled','succeeded','failed')),
    stage TEXT NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    total_count INTEGER,
    completed_count INTEGER NOT NULL DEFAULT 0,
    error_code TEXT,
    manifest TEXT,
    idempotency_key TEXT UNIQUE,
    request_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    started_at TEXT,
    finished_at TEXT,
    title_explicit INTEGER NOT NULL DEFAULT 1 CHECK(title_explicit IN (0,1))
);
INSERT INTO tasks_new SELECT *,1 FROM tasks;
DROP TABLE tasks;
ALTER TABLE tasks_new RENAME TO tasks;
CREATE INDEX tasks_queue ON tasks(status,created_at,id);
CREATE UNIQUE INDEX tasks_active_source ON tasks(source_path) WHERE status IN ('queued','running','cancelling');
