ALTER TABLE outbox_message
    ADD PRIMARY KEY (event_id);

ALTER TABLE recovery_task
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'QUEUED';

-- 历史错误记录没有可靠的归属修复依据，删除后由用户后续阅读自然重建。
DELETE history
FROM reading_history history
JOIN chapter chapter_row ON chapter_row.id = history.chapter_id
WHERE history.comic_id <> chapter_row.comic_id;

ALTER TABLE chapter
    ADD UNIQUE INDEX uk_chapter_comic_id (comic_id, id);

ALTER TABLE reading_history
    ADD INDEX idx_history_comic_chapter (comic_id, chapter_id),
    ADD CONSTRAINT fk_history_chapter_comic
        FOREIGN KEY (comic_id, chapter_id)
        REFERENCES chapter (comic_id, id)
        ON DELETE CASCADE;
