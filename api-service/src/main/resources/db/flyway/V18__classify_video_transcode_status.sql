-- ============================================================
-- V18: 修正历史视频转码状态（前向数据迁移，不改表结构）
-- ============================================================
-- 背景：导入/管理链路此前未按 VideoCompatibilityPolicy 判定转码需求，历史页面可能出现：
--   - IMAGE 页 transcode_status 异常（非 NOT_NEEDED，或旧库该列允许 NULL）
--   - VIDEO 页即使浏览器不兼容也被置为 NOT_NEEDED，导致永不进入转码
-- 本迁移的条件与 api-service common/media/VideoCompatibilityPolicy.classify 完全一致
-- （全项目唯一兼容矩阵，禁止在 SQL 中另起一套规则）：
--   兼容组合（大小写不敏感，null/空白视为未知）：
--     mp4|m4v + h264|avc|avc1 + 空音频|aac
--     webm   + vp8|vp9|av1   + 空音频|opus|vorbis
--   其余（含任一关键字段未知）→ REQUIRED
-- 仅重分类 VIDEO 且当前为 NOT_NEEDED 的行；QUEUED/TRANSCODING/READY/FAILED 原样保留。
-- 幂等：重复执行不产生额外改动（IMAGE 已归一、VIDEO 已重分类后无行命中）。
-- ============================================================

-- 1) IMAGE 归一为 NOT_NEEDED（异常/null → 默认；当前列已 NOT NULL，NULL 分支仅防御旧库）
UPDATE page SET transcode_status = 'NOT_NEEDED'
WHERE media_type = 'IMAGE' AND (transcode_status IS NULL OR transcode_status <> 'NOT_NEEDED');

-- 2) VIDEO 且当前 NOT_NEEDED → 按兼容矩阵重分类
--    与 VideoCompatibilityPolicy 一致：COALESCE(LOWER(TRIM(...)), '') 将 NULL 容器/编码
--    归一为空串 → 不命中矩阵 → 归为 REQUIRED（"未知字段需转码"语义）。
--    audio_codec 为 NULL/空白视为"空音频"→ 兼容（这是唯一允许未知的字段）。
UPDATE page SET transcode_status = 'REQUIRED'
WHERE media_type = 'VIDEO' AND transcode_status = 'NOT_NEEDED'
  AND NOT (
    (COALESCE(LOWER(TRIM(container)), '') IN ('mp4', 'm4v')
     AND COALESCE(LOWER(TRIM(video_codec)), '') IN ('h264', 'avc', 'avc1')
     AND (audio_codec IS NULL OR TRIM(audio_codec) = '' OR LOWER(TRIM(audio_codec)) IN ('aac')))
    OR
    (COALESCE(LOWER(TRIM(container)), '') = 'webm'
     AND COALESCE(LOWER(TRIM(video_codec)), '') IN ('vp8', 'vp9', 'av1')
     AND (audio_codec IS NULL OR TRIM(audio_codec) = '' OR LOWER(TRIM(audio_codec)) IN ('opus', 'vorbis')))
  );
