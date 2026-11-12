CREATE TABLE task_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL CHECK (event_type IN ('baseline','created','status_changed','stage_changed','progress_updated','retry_queued')),
    status TEXT NOT NULL,
    stage TEXT NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt >= 0),
    total_count INTEGER,
    completed_count INTEGER NOT NULL CHECK (completed_count >= 0),
    error_code TEXT,
    occurred_at TEXT NOT NULL
);
CREATE INDEX task_events_task_cursor ON task_events(task_id, id);

INSERT INTO task_events(task_id,event_type,status,stage,attempt,total_count,completed_count,error_code,occurred_at)
SELECT id,'baseline',status,stage,attempt,total_count,completed_count,error_code,strftime('%Y-%m-%dT%H:%M:%f000Z','now') FROM tasks;

CREATE TRIGGER task_events_after_insert AFTER INSERT ON tasks BEGIN
    INSERT INTO task_events(task_id,event_type,status,stage,attempt,total_count,completed_count,error_code,occurred_at)
    VALUES(NEW.id,'created',NEW.status,NEW.stage,NEW.attempt,NEW.total_count,NEW.completed_count,NEW.error_code,NEW.created_at);
END;
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
      CASE WHEN NEW.attempt > OLD.attempt THEN 'retry_queued'
           WHEN NEW.status IS NOT OLD.status THEN 'status_changed'
           WHEN NEW.stage IS NOT OLD.stage THEN 'stage_changed'
           ELSE 'progress_updated' END,
      NEW.status,NEW.stage,NEW.attempt,NEW.total_count,NEW.completed_count,NEW.error_code,
      strftime('%Y-%m-%dT%H:%M:%f000Z','now'));
END;
