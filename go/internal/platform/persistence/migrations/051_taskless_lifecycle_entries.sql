CREATE TABLE lifecycle_entries_next (
 id TEXT PRIMARY KEY NOT NULL,
 target_type TEXT NOT NULL CHECK(target_type IN('comic','chapter','media')),
 target_id TEXT NOT NULL,comic_id TEXT NOT NULL,chapter_id TEXT NOT NULL,title TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN('trashing','trashed','restoring','restored','purging','purged','detached')),
 plan_json TEXT NOT NULL CHECK(json_valid(plan_json)),created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
 origin TEXT NOT NULL DEFAULT 'native' CHECK(origin IN('native','legacy')),
 task_id TEXT REFERENCES tasks(id),
 CHECK((origin='native' AND task_id IS NOT NULL) OR (origin='legacy' AND task_id IS NULL AND state='trashed'))
);
INSERT INTO lifecycle_entries_next(id,target_type,target_id,comic_id,chapter_id,title,state,plan_json,created_at,updated_at,origin,task_id)
 SELECT id,target_type,target_id,comic_id,chapter_id,title,state,plan_json,created_at,updated_at,'native',task_id FROM lifecycle_entries;
DROP TABLE lifecycle_entries;
ALTER TABLE lifecycle_entries_next RENAME TO lifecycle_entries;
CREATE UNIQUE INDEX lifecycle_target_open ON lifecycle_entries(target_type,target_id) WHERE state NOT IN('restored','purged','detached');
CREATE INDEX lifecycle_entries_recent ON lifecycle_entries(updated_at DESC,id DESC);
CREATE INDEX lifecycle_entries_scope ON lifecycle_entries(comic_id,chapter_id,id) WHERE state NOT IN('restored','purged','detached');
CREATE TRIGGER comic_change_lifecycle_entries_insert AFTER INSERT ON lifecycle_entries BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_lifecycle_entries_update AFTER UPDATE ON lifecycle_entries BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id UNION SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_lifecycle_entries_delete AFTER DELETE ON lifecycle_entries BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id);
END;
