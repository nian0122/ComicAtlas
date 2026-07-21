CREATE TABLE IF NOT EXISTS comic (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    title_jpn VARCHAR(255),
    author VARCHAR(255),
    description TEXT,
    cover_path VARCHAR(512),
    total_pages INT DEFAULT 0,
    file_size BIGINT DEFAULT 0,
    hq_size BIGINT DEFAULT 0,
    lq_size BIGINT DEFAULT 0,
    source_type VARCHAR(16),
    source_gallery_id VARCHAR(64),
    source_gallery_token VARCHAR(32),
    source_ref VARCHAR(512),
    storage_policy VARCHAR(16) DEFAULT 'MANAGED',
    status VARCHAR(16) DEFAULT 'IMPORTING',
    category_id BIGINT,
    deleted_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_source (source_type, source_gallery_id),
    INDEX idx_status (status),
    INDEX idx_category_id (category_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS catalog (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id BIGINT NOT NULL,
    parent_id BIGINT DEFAULT NULL,
    title VARCHAR(255) NOT NULL,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_comic_parent_title (comic_id, parent_id, title),
    INDEX idx_comic_parent (comic_id, parent_id),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES catalog(id) ON DELETE CASCADE
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
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_catalog_chapter (comic_id, catalog_id, chapter_no),
    INDEX idx_comic_global (comic_id, global_order),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (catalog_id) REFERENCES catalog(id) ON DELETE SET NULL
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
    lq_size BIGINT DEFAULT 0,
    width INT,
    height INT,
    file_size BIGINT,
    media_type VARCHAR(32) NOT NULL DEFAULT 'IMAGE',
    duration DECIMAL(10,3) DEFAULT NULL,
    container VARCHAR(32) DEFAULT NULL,
    video_codec VARCHAR(32) DEFAULT NULL,
    audio_codec VARCHAR(32) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_chapter_page (chapter_id, page_number),
    INDEX idx_media_type (media_type),
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

CREATE TABLE IF NOT EXISTS import_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id BIGINT,
    source_ref VARCHAR(512),
    source_type VARCHAR(16) DEFAULT NULL,
    source_path VARCHAR(1024) DEFAULT NULL,
    status VARCHAR(16) DEFAULT 'PENDING',
    progress INT DEFAULT 0,
    total_pages INT,
    downloaded_pages INT DEFAULT 0,
    current_page INT DEFAULT 0,
    downloaded_bytes BIGINT DEFAULT 0,
    download_method VARCHAR(32) DEFAULT 'HTTP',
    download_speed BIGINT DEFAULT 0,
    eta_seconds INT DEFAULT 0,
    error_message VARCHAR(1024),
    retry_count INT DEFAULT 0,
    start_time DATETIME,
    end_time DATETIME,
    duration_ms BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reading_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    page_number INT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_comic (comic_id),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


