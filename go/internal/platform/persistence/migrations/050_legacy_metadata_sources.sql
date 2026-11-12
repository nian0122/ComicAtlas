-- 保留旧资料的选定原投影和转换结果；独立于当前业务编辑，不作为运行查询来源。
-- 这是整库迁移来源档案，生命周期随完整目标库及备份保留，不冒充旧任务历史。
CREATE TABLE legacy_metadata_sources (
    table_name TEXT NOT NULL CHECK(table_name IN ('category','tag','comic','catalog','chapter','comic_tag','reading_history','settings')),
    source_id TEXT NOT NULL CHECK(length(source_id) > 0),
    source_row INTEGER NOT NULL CHECK(source_row > 0),
    metadata_json TEXT NOT NULL CHECK(length(CAST(metadata_json AS BLOB)) <= 2097152 AND json_valid(metadata_json) AND json_type(metadata_json) = 'object'),
    recorded_at TEXT NOT NULL,
    PRIMARY KEY(table_name,source_id),
    UNIQUE(table_name,source_row)
) WITHOUT ROWID;
