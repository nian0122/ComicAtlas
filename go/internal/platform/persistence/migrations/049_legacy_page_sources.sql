-- 历史时间、版本、选定投影及实测文件事实随页面持久化；不伪造原版任务。
-- 仅由未发布迁移写入。媒体删除时同步清除，完整数据库备份保留此记录。
CREATE TABLE legacy_page_sources (
    media_id TEXT PRIMARY KEY NOT NULL REFERENCES media(id) ON DELETE CASCADE,
    source_version INTEGER NOT NULL CHECK(source_version > 0),
    created_at TEXT NOT NULL,
    source_json TEXT NOT NULL CHECK(length(CAST(source_json AS BLOB)) <= 2097152 AND json_valid(source_json) AND json_type(source_json) = 'object'),
    changes_json TEXT NOT NULL CHECK(length(CAST(changes_json AS BLOB)) <= 65536 AND json_valid(changes_json) AND json_type(changes_json) = 'array'),
    recorded_at TEXT NOT NULL
);
