-- V8: page 表新增 transcode_status 字段，跟踪视频转码状态
-- 默认值 'NOT_NEEDED' 表示非视频页面或兼容格式视频无需转码
-- 历史 VIDEO 页面若非 mp4/webm 或其 container 未分析，标记为 PENDING 待转码

ALTER TABLE page ADD COLUMN transcode_status VARCHAR(16) NOT NULL DEFAULT 'NOT_NEEDED';
ALTER TABLE page ADD INDEX idx_transcode_status (transcode_status);
UPDATE page SET transcode_status = 'PENDING' WHERE media_type = 'VIDEO' AND (container IS NULL OR container NOT IN ('mp4', 'webm'));
