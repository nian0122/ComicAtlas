-- ComicAtlas V21：统一 chapter/page 表排序规则与 comic 表一致（utf8mb4_unicode_ci）
-- 背景：V1 中 comic 表显式 COLLATE=utf8mb4_unicode_ci，而 chapter/page 表仅指定
-- DEFAULT CHARSET=utf8mb4 未固定排序规则，在 MySQL 8 默认 utf8mb4_0900_ai_ci 下发生漂移，
-- 导致跨表 UNION 报 Illegal mix of collations（MySQL 1271）。
-- 本迁移将 chapter/page 表数据与索引统一转换到 utf8mb4_unicode_ci（幂等，重复执行无副作用）。

ALTER TABLE chapter CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE page CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
