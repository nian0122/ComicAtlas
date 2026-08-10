-- ============================================================
-- ComicAtlas 元数据刷新 Worker 集成测试专用数据库初始化
-- 覆盖 Worker 只读查询所需的最小 schema（comic/catalog/chapter/page）
-- + api_user(可写) / worker_user(只读) 权限隔离。
-- ============================================================

CREATE TABLE IF NOT EXISTS comic (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    author VARCHAR(255),
    category VARCHAR(64),
    category_id BIGINT,
    cover_path VARCHAR(512),
    status VARCHAR(16) DEFAULT 'READY',
    storage_policy VARCHAR(16) DEFAULT 'MANAGED',
    total_pages INT DEFAULT 0,
    file_size BIGINT DEFAULT 0,
    hq_size BIGINT DEFAULT 0,
    source_type VARCHAR(16),
    source_gallery_id VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_source (source_type, source_gallery_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS catalog (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id BIGINT NOT NULL,
    parent_id BIGINT DEFAULT NULL,
    title VARCHAR(255) NOT NULL,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_comic_parent_title (comic_id, parent_id, title),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chapter (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id BIGINT NOT NULL,
    catalog_id BIGINT DEFAULT NULL,
    title VARCHAR(255),
    chapter_no VARCHAR(32) DEFAULT '1',
    page_count INT DEFAULT 0,
    sort_order INT DEFAULT 0,
    global_order INT DEFAULT 0,
    version INT DEFAULT 0,
    status VARCHAR(16) DEFAULT 'READY',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_catalog_chapter (comic_id, catalog_id, chapter_no),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS page (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chapter_id BIGINT NOT NULL,
    page_number INT NOT NULL,
    hq_root VARCHAR(32) DEFAULT 'HQ',
    hq_path VARCHAR(512),
    lq_root VARCHAR(32) DEFAULT NULL,
    lq_path VARCHAR(512),
    hq_status VARCHAR(16) DEFAULT 'PENDING',
    lq_status VARCHAR(16) DEFAULT 'NOT_GENERATED',
    transcode_status VARCHAR(16) NOT NULL DEFAULT 'NOT_NEEDED',
    lq_size BIGINT DEFAULT 0,
    width INT,
    height INT,
    file_size BIGINT,
    media_type VARCHAR(32) NOT NULL DEFAULT 'IMAGE',
    duration DECIMAL(10,3) DEFAULT NULL,
    container VARCHAR(32) DEFAULT NULL,
    video_codec VARCHAR(32) DEFAULT NULL,
    audio_codec VARCHAR(32) DEFAULT NULL,
    status VARCHAR(16) DEFAULT 'READY',
    version INT DEFAULT 0,
    original_page_number INT,
    trashed_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_chapter_page (chapter_id, page_number),
    FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(32),
    UNIQUE INDEX idx_name_type (name, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comic_tag (
    comic_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (comic_id, tag_id),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tag(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL UNIQUE,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ======== 用户权限 ========

CREATE USER IF NOT EXISTS 'api_user'@'%' IDENTIFIED BY 'api_test_pass';
GRANT ALL PRIVILEGES ON comic_atlas_test.* TO 'api_user'@'%';

CREATE USER IF NOT EXISTS 'worker_user'@'%' IDENTIFIED BY 'worker_test_pass';
GRANT SELECT ON comic_atlas_test.* TO 'worker_user'@'%';

FLUSH PRIVILEGES;
