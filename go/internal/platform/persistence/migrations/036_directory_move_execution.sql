CREATE TABLE lifecycle_entries_next (
 id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id),
 target_type TEXT NOT NULL CHECK(target_type IN('comic','chapter','media')),target_id TEXT NOT NULL,comic_id TEXT NOT NULL,chapter_id TEXT NOT NULL,title TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN('trashing','trashed','restoring','restored','purging','purged','detached')),plan_json TEXT NOT NULL CHECK(json_valid(plan_json)),
 created_at TEXT NOT NULL,updated_at TEXT NOT NULL,task_id TEXT NOT NULL REFERENCES tasks(id)
);
INSERT INTO lifecycle_entries_next SELECT * FROM lifecycle_entries;
DROP TABLE lifecycle_entries;
ALTER TABLE lifecycle_entries_next RENAME TO lifecycle_entries;
CREATE UNIQUE INDEX lifecycle_target_open ON lifecycle_entries(target_type,target_id) WHERE state NOT IN('restored','purged','detached');
CREATE INDEX lifecycle_entries_recent ON lifecycle_entries(updated_at DESC,id DESC);
CREATE INDEX lifecycle_entries_scope ON lifecycle_entries(comic_id,chapter_id,id) WHERE state NOT IN('restored','purged','detached');
CREATE TABLE directory_move_entries (
 task_id TEXT NOT NULL REFERENCES directory_move_runs(task_id),entry_id TEXT NOT NULL UNIQUE REFERENCES lifecycle_entries(id),
 PRIMARY KEY(task_id,entry_id)
);
CREATE TABLE directory_move_results (
 task_id TEXT NOT NULL REFERENCES directory_move_runs(task_id),position INTEGER NOT NULL CHECK(position BETWEEN 1 AND 100000),
 comic_id TEXT NOT NULL,source TEXT NOT NULL,target TEXT NOT NULL,
 size INTEGER NOT NULL CHECK(size>=0),sha256 TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN('pending','linked','moved','initially_missing')),
 PRIMARY KEY(task_id,position)
);
CREATE INDEX directory_move_result_state ON directory_move_results(task_id,state);
CREATE TRIGGER directory_move_result_immutable BEFORE UPDATE OF task_id,position,comic_id,source,target,size,sha256 ON directory_move_results
BEGIN SELECT RAISE(ABORT,'移出文件身份已冻结'); END;
