CREATE TABLE shared_media_references (
 media_id TEXT PRIMARY KEY NOT NULL REFERENCES media(id) ON DELETE CASCADE,
 hq_path TEXT NOT NULL,
 lq_path TEXT NOT NULL,
 hq_state TEXT NOT NULL,
 lq_state TEXT NOT NULL,
 source_reference TEXT NOT NULL CHECK(length(source_reference)=64)
);
CREATE TABLE shared_unresolved_history (
 source_id TEXT PRIMARY KEY NOT NULL,
 source_json TEXT NOT NULL,
 reason TEXT NOT NULL CHECK(reason='source_anchor_missing')
);
