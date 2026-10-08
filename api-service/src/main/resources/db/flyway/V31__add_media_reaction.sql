ALTER TABLE page
    ADD COLUMN reaction VARCHAR(16) NOT NULL DEFAULT 'NONE' AFTER audio_codec,
    ADD COLUMN reaction_at DATETIME DEFAULT NULL AFTER reaction,
    ADD INDEX idx_page_reaction_time (reaction, reaction_at);
