-- V10: 三层生命周期状态 + 乐观锁 + 状态值迁移
-- ============================================
-- 1. comic 表：状态值迁移 + version 列
-- ============================================

-- 1a. 旧状态值迁移（先迁移、再改列、避免约束冲突）
UPDATE comic SET status = 'RECOVERY_REQUIRED' WHERE status = 'PLACEHOLDER';
UPDATE comic SET status = 'IMPORTING'          WHERE status = 'REFRESHING';
UPDATE comic SET status = 'RECOVERY_REQUIRED'  WHERE status = 'RESCANNING';
UPDATE comic SET status = 'DELETING'           WHERE status = 'TRASHING' AND status IS NOT NULL;

-- 1b. version 乐观锁列
ALTER TABLE comic ADD COLUMN version INT DEFAULT 1 NOT NULL;

-- ============================================
-- 2. chapter 表：新增 status + version 列
-- ============================================
ALTER TABLE chapter ADD COLUMN status VARCHAR(16) DEFAULT 'READY' NOT NULL;
ALTER TABLE chapter ADD COLUMN version INT DEFAULT 1 NOT NULL;

-- ============================================
-- 3. page 表：新增 status + version 列，迁移 transcode 值
-- ============================================

-- 3a. transcode 值迁移：PENDING→QUEUED, PROCESSING→TRANSCODING, DONE→READY
UPDATE page SET transcode_status = 'QUEUED'     WHERE transcode_status = 'PENDING';
UPDATE page SET transcode_status = 'TRANSCODING' WHERE transcode_status = 'PROCESSING';
UPDATE page SET transcode_status = 'READY'       WHERE transcode_status = 'DONE';

-- 3b. 新增页面生命周期状态列
ALTER TABLE page ADD COLUMN status VARCHAR(16) DEFAULT 'READY' NOT NULL;
ALTER TABLE page ADD COLUMN version INT DEFAULT 1 NOT NULL;

-- ============================================
-- 4. import_task：迁移旧状态值
-- ============================================
UPDATE import_task SET status = 'SUCCESS' WHERE status = 'DONE';
UPDATE import_task SET status = 'FAILED'  WHERE status = 'ERROR';

-- ============================================
-- 5. recovery_task：状态值对齐 ManagementTaskStatus
-- ============================================
UPDATE recovery_task SET status = 'QUEUED'    WHERE status = 'PENDING';
UPDATE recovery_task SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';
