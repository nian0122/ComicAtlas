CREATE TABLE app_metadata (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);
INSERT INTO app_metadata (key, value) VALUES ('format', 'comicatlas-go-v1');
