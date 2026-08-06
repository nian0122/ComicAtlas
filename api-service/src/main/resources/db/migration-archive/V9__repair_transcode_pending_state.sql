UPDATE page
SET transcode_status = 'NOT_NEEDED'
WHERE media_type = 'VIDEO'
  AND transcode_status = 'PENDING'
  AND (container IS NULL OR container NOT IN ('mp4', 'webm'));
