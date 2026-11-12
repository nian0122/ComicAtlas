-- 与现有 Unicode 子串匹配使用同一确定性归一化；写入时维护，查询不逐行回调。
CREATE INDEX comics_active_search_text ON comics(
 COMIC_LOWER(title||' '||COALESCE(original_title,'')||' '||author),id
) WHERE lifecycle_state='active';
