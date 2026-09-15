-- V26 Java 迁移完成回填后收紧约束，阻止遗漏排序键的新写入。
ALTER TABLE comic MODIFY COLUMN title_sort_key VARBINARY(16384) NOT NULL COMMENT 'ICU 78.3 中文数字排序键';
