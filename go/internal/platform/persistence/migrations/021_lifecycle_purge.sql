CREATE TABLE lifecycle_entries_next (
 id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id),
 target_type TEXT NOT NULL CHECK(target_type IN('comic','chapter','media')),target_id TEXT NOT NULL,comic_id TEXT NOT NULL,chapter_id TEXT NOT NULL,title TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN('trashing','trashed','restoring','restored','purging','purged')),plan_json TEXT NOT NULL CHECK(json_valid(plan_json)),
 created_at TEXT NOT NULL,updated_at TEXT NOT NULL,task_id TEXT NOT NULL REFERENCES tasks(id)
);
INSERT INTO lifecycle_entries_next SELECT * FROM lifecycle_entries;
DROP TABLE lifecycle_entries;
ALTER TABLE lifecycle_entries_next RENAME TO lifecycle_entries;
CREATE UNIQUE INDEX lifecycle_target_open ON lifecycle_entries(target_type,target_id) WHERE state NOT IN('restored','purged');
CREATE INDEX lifecycle_entries_recent ON lifecycle_entries(updated_at DESC,id DESC);
CREATE INDEX lifecycle_entries_scope ON lifecycle_entries(comic_id,chapter_id,id) WHERE state NOT IN('restored','purged');
CREATE TABLE lifecycle_runs_next (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id),entry_id TEXT NOT NULL REFERENCES lifecycle_entries(id),comic_id TEXT NOT NULL,
 operation TEXT NOT NULL CHECK(operation IN('trash','restore','purge')),completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN(0,1))
);
INSERT INTO lifecycle_runs_next SELECT * FROM lifecycle_runs;
DROP TABLE lifecycle_runs;
ALTER TABLE lifecycle_runs_next RENAME TO lifecycle_runs;
CREATE UNIQUE INDEX lifecycle_comic_lock ON lifecycle_runs(comic_id) WHERE completed=0;
CREATE TABLE lifecycle_confirmations (
 token_hash TEXT PRIMARY KEY NOT NULL CHECK(length(token_hash)=64),entry_id TEXT NOT NULL REFERENCES lifecycle_entries(id),
 scope_hash TEXT NOT NULL CHECK(length(scope_hash)=64),retention_days INTEGER NOT NULL CHECK(retention_days BETWEEN 0 AND 3650),
 expires_at TEXT NOT NULL,consumed_by TEXT UNIQUE REFERENCES tasks(id)
);
CREATE INDEX lifecycle_confirmations_expiry ON lifecycle_confirmations(expires_at);
CREATE TABLE lifecycle_purge_entries (
 task_id TEXT NOT NULL REFERENCES lifecycle_runs(task_id),entry_id TEXT NOT NULL REFERENCES lifecycle_entries(id),
 plan_hash TEXT NOT NULL CHECK(length(plan_hash)=64),PRIMARY KEY(task_id,entry_id)
);
