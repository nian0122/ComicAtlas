-- ICU 排序键使用完整 VARBINARY 比较，避免 TEXT/BLOB 受 max_sort_length 截断影响。
ALTER TABLE comic ADD COLUMN title_sort_key VARBINARY(16384) NULL COMMENT 'ICU 78.3 中文数字排序键';
