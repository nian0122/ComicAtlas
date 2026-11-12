DROP TRIGGER task_events_after_update;

UPDATE task_events
SET event_type='status_changed'
WHERE event_type='retry_queued' AND status='running';

CREATE TRIGGER task_events_after_update AFTER UPDATE ON tasks
WHEN OLD.status IS NOT NEW.status OR OLD.stage IS NOT NEW.stage OR OLD.attempt IS NOT NEW.attempt
  OR OLD.error_code IS NOT NEW.error_code
  OR OLD.total_count IS NOT NEW.total_count
  OR (OLD.completed_count IS NOT NEW.completed_count
      AND (NEW.completed_count / CASE WHEN NEW.total_count > 0 THEN max(1, NEW.total_count / 100) ELSE 100 END)
       != (OLD.completed_count / CASE WHEN OLD.total_count > 0 THEN max(1, OLD.total_count / 100) ELSE 100 END))
BEGIN
    INSERT INTO task_events(task_id,event_type,status,stage,attempt,total_count,completed_count,error_code,occurred_at)
    VALUES(NEW.id,
      CASE WHEN OLD.status='failed' AND NEW.status='queued' THEN 'retry_queued'
           WHEN NEW.status IS NOT OLD.status THEN 'status_changed'
           WHEN NEW.stage IS NOT OLD.stage THEN 'stage_changed'
           ELSE 'progress_updated' END,
      NEW.status,NEW.stage,NEW.attempt,NEW.total_count,NEW.completed_count,NEW.error_code,
      strftime('%Y-%m-%dT%H:%M:%f000Z','now'));
END;
