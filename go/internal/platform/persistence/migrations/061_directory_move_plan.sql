-- 新协议只消费唯一版本 3 计划；升级前必须完成旧的未结束移出任务，禁止丢弃唯一原件。
CREATE TABLE directory_move_upgrade_guard(unfinished INTEGER CHECK(unfinished=0));
INSERT INTO directory_move_upgrade_guard SELECT COUNT(*) FROM directory_move_runs WHERE completed=0;
DROP TABLE directory_move_upgrade_guard;
-- 未受理旧预览失效，调用方必须重新预检，不把旧范围排入新版执行器。
DELETE FROM directory_move_previews WHERE task_id IS NULL;
DROP TRIGGER directory_move_seal_immutable;
ALTER TABLE directory_move_runs DROP COLUMN seal_json;
ALTER TABLE directory_move_runs ADD COLUMN report_json TEXT CHECK(report_json IS NULL OR json_valid(report_json));
CREATE TABLE directory_move_results_next(
 task_id TEXT NOT NULL REFERENCES directory_move_runs(task_id),position INTEGER NOT NULL CHECK(position BETWEEN 1 AND 100000),
 comic_id TEXT NOT NULL,source TEXT NOT NULL,target TEXT NOT NULL,
 size INTEGER NOT NULL CHECK(size>=0),sha256 TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN('moved','initially_missing')),
 PRIMARY KEY(task_id,position)
);
INSERT INTO directory_move_results_next SELECT * FROM directory_move_results;
DROP TABLE directory_move_results;
ALTER TABLE directory_move_results_next RENAME TO directory_move_results;
CREATE TRIGGER directory_move_result_immutable BEFORE UPDATE ON directory_move_results
BEGIN SELECT RAISE(ABORT,'已完成移出结果不可改写'); END;
