CREATE TABLE video_transcode_items_next (
 task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
 media_id TEXT NOT NULL,
 position INTEGER NOT NULL CHECK(position BETWEEN 1 AND 10000),
 source_name TEXT NOT NULL, source_path TEXT NOT NULL, source_sha256 TEXT NOT NULL CHECK(length(source_sha256) IN (0,64)), source_size INTEGER NOT NULL CHECK(source_size>0),
 status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','prepared','completed','skipped','failed')),
 error_code TEXT,
 output_json TEXT CHECK(output_json IS NULL OR (json_valid(output_json) AND json_type(output_json)='object')),
 collected INTEGER NOT NULL DEFAULT 0 CHECK(collected IN (0,1)),
 PRIMARY KEY(task_id,media_id), UNIQUE(task_id,position),
 CHECK(status NOT IN ('prepared','completed') OR output_json IS NOT NULL)
);
INSERT INTO video_transcode_items_next SELECT * FROM video_transcode_items;
DROP TABLE video_transcode_items;
ALTER TABLE video_transcode_items_next RENAME TO video_transcode_items;
CREATE INDEX video_transcode_pending ON video_transcode_items(task_id,status,position);
CREATE INDEX video_transcode_prepared ON video_transcode_items(task_id) WHERE status='prepared';
