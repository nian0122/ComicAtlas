-- ============================================================
-- V16: 回收站生命周期扩展列
-- trashed_at：进入 TRASHED 的时间，7 天保留期起点
-- original_page_number：媒体回收前原页码，恢复时优先复用
-- ============================================================

ALTER TABLE comic
    ADD COLUMN trashed_at DATETIME COMMENT '进入 TRASHED 的时间（7 天保留期起点）' AFTER deleted_at;

ALTER TABLE chapter
    ADD COLUMN trashed_at DATETIME COMMENT '进入 TRASHED 的时间（7 天保留期起点）' AFTER status;

ALTER TABLE page
    ADD COLUMN original_page_number INT COMMENT '回收前原页码，恢复时优先复用' AFTER page_number;

ALTER TABLE page
    ADD COLUMN trashed_at DATETIME COMMENT '进入 TRASHED 的时间（7 天保留期起点）' AFTER status;
