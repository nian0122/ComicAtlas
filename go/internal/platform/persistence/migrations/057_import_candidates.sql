ALTER TABLE import_candidates DROP COLUMN fingerprint;
ALTER TABLE import_candidates RENAME COLUMN preview_json TO candidate_json;
CREATE TABLE import_source_claims(task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id),source_path TEXT NOT NULL UNIQUE);
