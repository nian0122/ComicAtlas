CREATE TABLE directory_move_upgrade_guard(unfinished INTEGER CHECK(unfinished=0));
INSERT INTO directory_move_upgrade_guard SELECT COUNT(*) FROM directory_move_runs WHERE completed=0;
DROP TABLE directory_move_upgrade_guard;
-- 旧导入可能已经持有唯一原件，先由原版本完成该任务。
CREATE TABLE import_upgrade_guard(unfinished INTEGER CHECK(unfinished=0));
INSERT INTO import_upgrade_guard SELECT COUNT(*) FROM import_source_claims;
DROP TABLE import_upgrade_guard;

DELETE FROM directory_move_previews WHERE task_id IS NULL;
ALTER TABLE directory_move_results DROP COLUMN sha256;
ALTER TABLE directory_move_results ADD COLUMN modified_ns INTEGER NOT NULL DEFAULT 0;
