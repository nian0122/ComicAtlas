ALTER TABLE export_artifacts DROP COLUMN sha256;
ALTER TABLE export_artifacts ADD COLUMN modified_ns INTEGER NOT NULL DEFAULT 0;
