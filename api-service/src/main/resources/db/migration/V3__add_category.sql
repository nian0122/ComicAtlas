-- V3: 新增 category 表与 comic.category_id，并迁移旧 category 字符串

CREATE TABLE IF NOT EXISTS category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 插入默认分类
INSERT INTO category (name, sort_order) VALUES
    ('未分类', 1),
    ('同人', 2),
    ('单本', 3),
    ('连载', 4),
    ('其他', 5)
ON DUPLICATE KEY UPDATE sort_order = VALUES(sort_order);

-- 新增 comic.category_id 列
ALTER TABLE comic ADD COLUMN IF NOT EXISTS category_id BIGINT NULL;

-- 将旧 category 字符串映射到 category_id
UPDATE comic c
    JOIN category cat ON cat.name = c.category
    SET c.category_id = cat.id
WHERE c.category IS NOT NULL AND c.category_id IS NULL;

-- 未匹配到的漫画设为“未分类”
UPDATE comic
    SET category_id = (SELECT id FROM category WHERE name = '未分类')
WHERE category_id IS NULL;

-- 创建外键（可选，生产环境建议保留）
-- ALTER TABLE comic ADD CONSTRAINT fk_comic_category
--     FOREIGN KEY (category_id) REFERENCES category(id);
