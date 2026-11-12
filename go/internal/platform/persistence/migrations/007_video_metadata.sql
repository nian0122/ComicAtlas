ALTER TABLE media ADD COLUMN video_metadata TEXT
    CHECK (video_metadata IS NULL OR (media_type='video' AND json_valid(video_metadata) AND json_type(video_metadata)='object'));
