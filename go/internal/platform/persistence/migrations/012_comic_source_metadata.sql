ALTER TABLE comics ADD COLUMN original_title TEXT CHECK(original_title IS NULL OR length(original_title)>0);
CREATE TABLE comic_source_metadata (
 comic_id TEXT PRIMARY KEY NOT NULL REFERENCES comics(id) ON DELETE CASCADE,
 comicinfo_json TEXT NOT NULL CHECK(json_valid(comicinfo_json) AND json_type(comicinfo_json)='object')
);
