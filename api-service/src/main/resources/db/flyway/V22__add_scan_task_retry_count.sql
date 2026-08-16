-- ComicAtlas V22：目录扫描任务新增重试次数列（支持失败重试追踪，与 import_task/recovery_task 一致）
ALTER TABLE directory_scan_task ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数';
