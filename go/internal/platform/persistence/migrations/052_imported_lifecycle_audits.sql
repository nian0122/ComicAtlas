CREATE TABLE lifecycle_audits_next (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id),entry_id TEXT NOT NULL REFERENCES lifecycle_entries(id),
 owner_task_id TEXT REFERENCES tasks(id),snapshot_hash TEXT NOT NULL CHECK(length(snapshot_hash)=64),
 total_files INTEGER NOT NULL CHECK(total_files BETWEEN 0 AND 100000),complete INTEGER NOT NULL DEFAULT 0 CHECK(complete IN(0,1)),
 summary_json TEXT CHECK(summary_json IS NULL OR json_valid(summary_json)),created_at TEXT NOT NULL
);
INSERT INTO lifecycle_audits_next SELECT * FROM lifecycle_audits;
DROP TABLE lifecycle_audits;
ALTER TABLE lifecycle_audits_next RENAME TO lifecycle_audits;
CREATE INDEX lifecycle_audits_latest ON lifecycle_audits(entry_id,created_at DESC,task_id DESC);
