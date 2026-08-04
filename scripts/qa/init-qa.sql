-- ============================================================
-- ComicAtlas QA E2E 初始化 SQL — 仅创建测试库与账号（不建业务表，
-- 业务表由 API 启动时的 Flyway 迁移在空库/升级库场景中自建）
-- ============================================================
CREATE DATABASE IF NOT EXISTS comic_atlas_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS comic_atlas_upgrade CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- API 与 Worker 共用测试账号（Worker 的只读权限由 WorkerDatabasePermissionIT 单独验证）
CREATE USER IF NOT EXISTS 'e2e_user'@'%' IDENTIFIED BY 'e2e_test_pass';
GRANT ALL PRIVILEGES ON comic_atlas_test.* TO 'e2e_user'@'%';
GRANT ALL PRIVILEGES ON comic_atlas_upgrade.* TO 'e2e_user'@'%';
FLUSH PRIVILEGES;
