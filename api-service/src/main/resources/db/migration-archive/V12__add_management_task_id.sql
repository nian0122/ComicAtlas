-- ============================================================
-- V12: 既有任务表增加 management_task_id 一对一扩展列
-- 不破坏现有数据和 ID，允许逐步回填关联
-- ============================================================

ALTER TABLE import_task
    ADD COLUMN management_task_id BIGINT COMMENT '关联 management_task.id 一对一扩展' AFTER id;

ALTER TABLE recovery_task
    ADD COLUMN management_task_id BIGINT COMMENT '关联 management_task.id 一对一扩展' AFTER id;

ALTER TABLE export_task
    ADD COLUMN management_task_id BIGINT COMMENT '关联 management_task.id 一对一扩展' AFTER id;

ALTER TABLE directory_scan_task
    ADD COLUMN management_task_id BIGINT COMMENT '关联 management_task.id 一对一扩展' AFTER id;
