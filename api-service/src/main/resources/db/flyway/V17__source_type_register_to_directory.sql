-- V17: source_type 枚举 REGISTER → DIRECTORY
-- 背景：SourceType 枚举将 REGISTER 更名为 DIRECTORY（本地目录导入语义），
-- 存量数据同步更新，避免读取时枚举不匹配。
UPDATE comic SET source_type = 'DIRECTORY' WHERE source_type = 'REGISTER';
UPDATE import_task SET source_type = 'DIRECTORY' WHERE source_type = 'REGISTER';
