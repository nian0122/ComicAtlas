-- V5: page 表新增媒体类型与视频元数据字段，支持图片+视频混排
-- 默认值 'IMAGE' 保证现有数据自动兼容

ALTER TABLE page
    ADD COLUMN media_type   VARCHAR(32)  NOT NULL DEFAULT 'IMAGE' AFTER file_size,
    ADD COLUMN duration     DECIMAL(10,3) NULL     AFTER media_type,
    ADD COLUMN container    VARCHAR(32)  NULL     AFTER duration,
    ADD COLUMN video_codec  VARCHAR(32)  NULL     AFTER container,
    ADD COLUMN audio_codec  VARCHAR(32)  NULL     AFTER video_codec,
    ADD INDEX idx_media_type (media_type);
