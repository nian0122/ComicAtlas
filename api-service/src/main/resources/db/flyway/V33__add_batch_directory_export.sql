ALTER TABLE export_task
    MODIFY COLUMN format VARCHAR(32) NOT NULL DEFAULT 'ZIP',
    ADD COLUMN comic_ids TEXT NULL COMMENT '批量文件夹导出的漫画 ID，逗号分隔' AFTER comic_id;
