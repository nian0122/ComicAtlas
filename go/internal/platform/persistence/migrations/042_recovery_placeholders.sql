CREATE TABLE comic_recovery_placeholders (
 comic_id TEXT PRIMARY KEY NOT NULL REFERENCES comics(id) ON DELETE CASCADE,
 task_id TEXT REFERENCES tasks(id) ON DELETE SET NULL,
 reason TEXT NOT NULL CHECK(reason='metadata_missing'),
 created_at TEXT NOT NULL,
 media_count INTEGER NOT NULL CHECK(media_count>=0),
 other_count INTEGER NOT NULL CHECK(other_count>=0),
 directory_count INTEGER NOT NULL CHECK(directory_count>=0),
 CHECK(media_count+other_count>0 AND media_count+other_count+directory_count<=200000)
);

CREATE TABLE recovery_results_next (
 task_id TEXT NOT NULL,
 comic_id TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN('restored','placeholder','skipped','empty','error')),
 task_attempt INTEGER NOT NULL CHECK(task_attempt>0),
 try_count INTEGER NOT NULL CHECK(try_count BETWEEN 1 AND 1000000),
 error_code TEXT CHECK(error_code IS NULL OR length(error_code) BETWEEN 1 AND 64),
 source_sha TEXT CHECK(source_sha IS NULL OR length(source_sha)=64),
 updated_at TEXT NOT NULL,
 PRIMARY KEY(task_id,comic_id),
 FOREIGN KEY(task_id,comic_id) REFERENCES recovery_candidates(task_id,comic_id) ON DELETE CASCADE,
 CHECK((status='error' AND error_code IS NOT NULL) OR (status<>'error' AND error_code IS NULL)),
 CHECK(status<>'restored' OR source_sha IS NOT NULL),
 CHECK(status<>'placeholder' OR source_sha IS NULL)
);
INSERT INTO recovery_results_next SELECT * FROM recovery_results;
DROP TRIGGER recovery_result_identity;
DROP TRIGGER recovery_result_insert;
DROP TRIGGER recovery_result_update;
DROP TRIGGER recovery_result_delete;
DROP TABLE recovery_results;
ALTER TABLE recovery_results_next RENAME TO recovery_results;
CREATE INDEX recovery_results_state ON recovery_results(task_id,status,task_attempt,comic_id);
CREATE TRIGGER recovery_result_identity BEFORE UPDATE OF task_id,comic_id ON recovery_results
BEGIN SELECT RAISE(ABORT,'恢复结果身份已冻结'); END;
CREATE TRIGGER recovery_result_insert BEFORE INSERT ON recovery_results
WHEN NOT EXISTS(SELECT 1 FROM recovery_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=NEW.task_id AND r.sealed=1 AND t.type='disk_recovery' AND t.status='running' AND t.attempt=NEW.task_attempt)
BEGIN SELECT RAISE(ABORT,'恢复执行已失效'); END;
CREATE TRIGGER recovery_result_update BEFORE UPDATE ON recovery_results
WHEN OLD.status<>'error' OR NEW.task_attempt<=OLD.task_attempt OR NEW.try_count<>OLD.try_count+1
 OR NOT EXISTS(SELECT 1 FROM recovery_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=NEW.task_id AND r.sealed=1 AND t.type='disk_recovery' AND t.status='running' AND t.attempt=NEW.task_attempt)
BEGIN SELECT RAISE(ABORT,'恢复结果不能被覆盖'); END;
CREATE TRIGGER recovery_result_delete BEFORE DELETE ON recovery_results
WHEN EXISTS(SELECT 1 FROM recovery_candidates c WHERE c.task_id=OLD.task_id AND c.comic_id=OLD.comic_id)
BEGIN SELECT RAISE(ABORT,'恢复结果不能被单独删除'); END;
