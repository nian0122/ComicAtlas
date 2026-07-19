ALTER TABLE import_task ADD COLUMN batch_id VARCHAR(36) DEFAULT NULL;
CREATE INDEX idx_import_task_batch_id ON import_task(batch_id);
