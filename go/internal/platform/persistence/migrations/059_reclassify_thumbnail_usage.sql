-- 旧扫描快照的缩略图占用转入其他，不保留旧 API 统计字段。
UPDATE storage_observations
SET result_json = json_remove(json_set(result_json,
    '$.other.files', COALESCE(json_extract(result_json, '$.other.files'), 0) + COALESCE(json_extract(result_json, '$.thumbnails.files'), 0),
    '$.other.bytes', COALESCE(json_extract(result_json, '$.other.bytes'), 0) + COALESCE(json_extract(result_json, '$.thumbnails.bytes'), 0)
), '$.thumbnails')
WHERE json_type(result_json, '$.thumbnails') IS NOT NULL;
