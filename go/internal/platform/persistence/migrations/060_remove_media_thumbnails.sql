-- 单页预览直接读取 HQ/LQ；删除已停用的缩略图登记与触发器，不保留兼容投影。
DROP VIEW media_file_ownership;
DROP TRIGGER thumbnails_deleted;
DROP TABLE media_thumbnails;
DROP TABLE thumbnail_garbage;
CREATE VIEW media_file_ownership AS
 WITH owned_media AS (
 SELECT m.*,ch.comic_id,CASE WHEN c.lifecycle_state<>'active' THEN c.lifecycle_state WHEN ch.lifecycle_state<>'active' THEN ch.lifecycle_state ELSE m.lifecycle_state END owner_state
 FROM media m JOIN chapters ch ON ch.id=m.chapter_id JOIN comics c ON c.id=ch.comic_id
 )
 SELECT comic_id,chapter_id,id media_id,'original' role,relative_path path,size,sha256,owner_state state FROM owned_media WHERE hq_state IN('available','releasing')
 UNION ALL SELECT m.comic_id,m.chapter_id,m.id,'lq',l.relative_path,l.size,l.sha256,m.owner_state FROM image_lq l JOIN owned_media m ON m.id=l.media_id
 UNION ALL SELECT c.comic_id,m.chapter_id,c.media_id,'cover',c.relative_path,c.size,c.sha256,b.lifecycle_state FROM comic_covers c JOIN comics b ON b.id=c.comic_id LEFT JOIN owned_media m ON m.id=c.media_id
 UNION ALL SELECT s.comic_id,s.chapter_id,s.replace_media_id,'retired_'||r.kind,'retired/uploads/'||s.id||'/'||r.kind||'.bin',r.size,r.sha256,r.status FROM upload_retired r JOIN upload_sessions s ON s.task_id=r.task_id WHERE r.status='moved'
 UNION ALL SELECT s.comic_id,s.chapter_id,f.media_id,'upload_pending',f.target_path,f.size,f.sha256,f.publish_state FROM upload_files f JOIN upload_sessions s ON s.id=f.session_id WHERE f.target_path IS NOT NULL AND s.status IN('submitted','cancelled','expired') AND s.cleaned=0
 UNION ALL SELECT id,NULL,NULL,'metadata','metadata/'||id||'.json',NULL,NULL,lifecycle_state FROM comics;
