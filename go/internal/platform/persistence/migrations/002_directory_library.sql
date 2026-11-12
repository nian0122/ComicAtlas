CREATE TABLE comics (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX comics_title_order ON comics(title COLLATE COMIC_NATURAL,id);
CREATE TABLE catalogs (
    id TEXT PRIMARY KEY NOT NULL,
    comic_id TEXT NOT NULL REFERENCES comics(id) ON DELETE CASCADE,
    parent_id TEXT REFERENCES catalogs(id),
    title TEXT NOT NULL,
    sort_order INTEGER NOT NULL
);
CREATE INDEX catalogs_comic ON catalogs(comic_id, sort_order);
CREATE TABLE chapters (
    id TEXT PRIMARY KEY NOT NULL,
    comic_id TEXT NOT NULL REFERENCES comics(id) ON DELETE CASCADE,
    catalog_id TEXT REFERENCES catalogs(id),
    title TEXT NOT NULL,
    global_order INTEGER NOT NULL,
    UNIQUE(comic_id, global_order)
);
CREATE TABLE media (
    id TEXT PRIMARY KEY NOT NULL,
    chapter_id TEXT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    page_number INTEGER NOT NULL CHECK(page_number > 0),
    file_name TEXT NOT NULL,
    relative_path TEXT NOT NULL UNIQUE,
    media_type TEXT NOT NULL CHECK(media_type IN ('image','video')),
    content_type TEXT NOT NULL,
    size INTEGER NOT NULL CHECK(size >= 0),
    width INTEGER,
    height INTEGER,
    sha256 TEXT NOT NULL,
    UNIQUE(chapter_id, page_number)
);
CREATE TABLE reading_progress (
    comic_id TEXT PRIMARY KEY NOT NULL REFERENCES comics(id) ON DELETE CASCADE,
    chapter_id TEXT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    page_number INTEGER NOT NULL CHECK(page_number > 0),
    updated_at TEXT NOT NULL
);
CREATE TABLE tasks (
    id TEXT PRIMARY KEY NOT NULL,
    type TEXT NOT NULL CHECK(type = 'directory_import'),
    source_path TEXT NOT NULL,
    title TEXT NOT NULL,
    comic_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('queued','running','cancelling','cancelled','succeeded','failed')),
    stage TEXT NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    total_count INTEGER,
    completed_count INTEGER NOT NULL DEFAULT 0,
    error_code TEXT,
    manifest TEXT,
    idempotency_key TEXT UNIQUE,
    request_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    started_at TEXT,
    finished_at TEXT
);
CREATE INDEX tasks_queue ON tasks(status, created_at, id);
CREATE UNIQUE INDEX tasks_active_source ON tasks(source_path) WHERE status IN ('queued','running','cancelling');
