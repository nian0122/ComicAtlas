CREATE DATABASE IF NOT EXISTS comic_atlas
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
USE comic_atlas;

CREATE TABLE IF NOT EXISTS comic (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    title_jpn VARCHAR(255),
    author VARCHAR(255),
    cover_path VARCHAR(512),
    total_pages INT DEFAULT 0,
    file_size BIGINT DEFAULT 0,
    hq_size BIGINT DEFAULT 0,
    lq_size BIGINT DEFAULT 0,
    source_type VARCHAR(16),
    source_gallery_id VARCHAR(64),
    source_gallery_token VARCHAR(32),
    source_ref VARCHAR(512),
    storage_type VARCHAR(16) DEFAULT 'FILESYSTEM',
    root_key VARCHAR(32) DEFAULT 'LOCAL',
    relative_path VARCHAR(512),
    status VARCHAR(16) DEFAULT 'IMPORTING',
    lq_status VARCHAR(16) DEFAULT NULL,
    category VARCHAR(64),
    deleted_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_source (source_type, source_gallery_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chapter (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id BIGINT NOT NULL,
    title VARCHAR(255),
    chapter_no VARCHAR(32) DEFAULT '1',
    page_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_comic_chapter (comic_id, chapter_no),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS page (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chapter_id BIGINT NOT NULL,
    page_number INT NOT NULL,
    image_name VARCHAR(255) NOT NULL,
    hq_status VARCHAR(16) DEFAULT 'PENDING',
    lq_status VARCHAR(16) DEFAULT 'PENDING',
    lq_size BIGINT DEFAULT 0,
    width INT,
    height INT,
    file_size BIGINT,
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

CREATE TABLE IF NOT EXISTS import_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id BIGINT,
    source_ref VARCHAR(512),
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

CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(64),
    module VARCHAR(32),
    action VARCHAR(64),
    business_id VARCHAR(64),
    detail TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_trace_id (trace_id),
    INDEX idx_module_business (module, business_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
