-- ============================================================
-- ComicAtlas 集成测试用户初始化
-- api_user: 完全读写权限（模拟 API Service 账号）
-- worker_user: 仅 SELECT 权限（强制 Worker 只读）
-- ============================================================

-- 创建 API 账号（完全读写）
CREATE USER IF NOT EXISTS 'api_user'@'%' IDENTIFIED BY 'api_test_pass';
GRANT ALL PRIVILEGES ON comic_atlas_test.* TO 'api_user'@'%';

-- 创建 Worker 账号（仅 SELECT）
CREATE USER IF NOT EXISTS 'worker_user'@'%' IDENTIFIED BY 'worker_test_pass';
GRANT SELECT ON comic_atlas_test.* TO 'worker_user'@'%';

FLUSH PRIVILEGES;
