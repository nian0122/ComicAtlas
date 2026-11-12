-- 只索引可见漫画；排序主键相同时仍按 ID 升序，与列表接口保持一致。
CREATE INDEX comics_active_author ON comics(author COLLATE COMIC_NATURAL,id)
 WHERE lifecycle_state='active';
CREATE INDEX comics_active_created_desc ON comics(created_at DESC,id)
 WHERE lifecycle_state='active';
CREATE INDEX comics_active_category_author ON comics(category_id,author COLLATE COMIC_NATURAL,id)
 WHERE lifecycle_state='active';
