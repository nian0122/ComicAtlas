CREATE TABLE upload_parts_new (
 session_id TEXT NOT NULL,file_id TEXT NOT NULL,part INTEGER NOT NULL CHECK(part BETWEEN 0 AND 1279),
 size INTEGER NOT NULL CHECK(size BETWEEN 1 AND 21474836480),sha256 TEXT NOT NULL CHECK(length(sha256)=64),
 PRIMARY KEY(session_id,file_id,part), FOREIGN KEY(session_id,file_id) REFERENCES upload_files(session_id,id) ON DELETE CASCADE
);
INSERT INTO upload_parts_new SELECT session_id,file_id,part,size,sha256 FROM upload_parts;
DROP TABLE upload_parts;
ALTER TABLE upload_parts_new RENAME TO upload_parts;
