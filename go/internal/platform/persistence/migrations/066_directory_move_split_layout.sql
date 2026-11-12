-- 版本 5 只消费漫画与其他文件分栏的计划；不转换旧计划。升级前必须完成未结束的移出任务，避免丢弃唯一原件。
CREATE TABLE directory_move_upgrade_guard(unfinished INTEGER CHECK(unfinished=0));
INSERT INTO directory_move_upgrade_guard SELECT COUNT(*) FROM directory_move_runs WHERE completed=0;
DROP TABLE directory_move_upgrade_guard;
-- 未受理旧预览失效，调用方必须重新预检。
DELETE FROM directory_move_previews WHERE task_id IS NULL;
