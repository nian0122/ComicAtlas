ALTER TABLE comic
    ADD COLUMN reaction VARCHAR(16) NOT NULL DEFAULT 'NONE' AFTER updated_at,
    ADD COLUMN reaction_at DATETIME DEFAULT NULL AFTER reaction,
    ADD INDEX idx_comic_reaction_time (reaction, reaction_at);

ALTER TABLE chapter
    ADD COLUMN reaction VARCHAR(16) NOT NULL DEFAULT 'NONE' AFTER trashed_at,
    ADD COLUMN reaction_at DATETIME DEFAULT NULL AFTER reaction,
    ADD INDEX idx_chapter_reaction_time (reaction, reaction_at);
