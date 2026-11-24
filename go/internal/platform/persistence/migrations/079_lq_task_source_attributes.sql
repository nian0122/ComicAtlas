ALTER TABLE image_lq_items ADD COLUMN source_size INTEGER NOT NULL DEFAULT -1;
ALTER TABLE image_lq_items ADD COLUMN source_modified_ns INTEGER NOT NULL DEFAULT 0;
-- 未完成任务只沿用仍指向同一媒体及路径的原件登记。
UPDATE image_lq_items AS item SET (source_size,source_modified_ns)=(
 SELECT m.size,m.modified_ns FROM media m WHERE m.id=item.media_id AND m.relative_path=item.source_path AND m.sha256=item.source_sha256
) WHERE item.status IN('pending','failed') AND EXISTS(
 SELECT 1 FROM media m WHERE m.id=item.media_id AND m.relative_path=item.source_path AND m.sha256=item.source_sha256
);
ALTER TABLE image_lq_items DROP COLUMN source_sha256;
