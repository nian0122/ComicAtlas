ALTER TABLE hq_release_items ADD COLUMN source_modified_ns INTEGER NOT NULL DEFAULT 0;
ALTER TABLE hq_release_items ADD COLUMN lq_width INTEGER NOT NULL DEFAULT 0;
ALTER TABLE hq_release_items ADD COLUMN lq_height INTEGER NOT NULL DEFAULT 0;
UPDATE hq_release_items AS item SET source_modified_ns=COALESCE((
 SELECT m.modified_ns FROM media m WHERE m.id=item.media_id AND m.relative_path=item.source_path AND m.size=item.original_bytes AND m.sha256=item.source_sha256
),0) WHERE item.status<>'released';
UPDATE hq_release_items AS item SET (lq_width,lq_height)=(
 SELECT l.width,l.height FROM image_lq l WHERE l.media_id=item.media_id AND l.relative_path=item.lq_path AND l.size=item.lq_bytes AND l.sha256=item.lq_sha256
) WHERE item.status<>'released' AND EXISTS(
 SELECT 1 FROM image_lq l WHERE l.media_id=item.media_id AND l.relative_path=item.lq_path AND l.size=item.lq_bytes AND l.sha256=item.lq_sha256
);
ALTER TABLE hq_release_items DROP COLUMN source_sha256;
ALTER TABLE hq_release_items DROP COLUMN lq_sha256;
