UPDATE tasks SET created_at=substr(created_at,1,19)||'.'||
    substr((CASE WHEN substr(created_at,20,1)='.' THEN substr(created_at,21,length(created_at)-21) ELSE '' END)||'000000000',1,9)||'Z';
UPDATE tasks SET started_at=substr(started_at,1,19)||'.'||
    substr((CASE WHEN substr(started_at,20,1)='.' THEN substr(started_at,21,length(started_at)-21) ELSE '' END)||'000000000',1,9)||'Z' WHERE started_at IS NOT NULL;
UPDATE tasks SET finished_at=substr(finished_at,1,19)||'.'||
    substr((CASE WHEN substr(finished_at,20,1)='.' THEN substr(finished_at,21,length(finished_at)-21) ELSE '' END)||'000000000',1,9)||'Z' WHERE finished_at IS NOT NULL;
CREATE INDEX tasks_recent ON tasks(created_at DESC,id DESC);
CREATE INDEX tasks_type_recent ON tasks(type,created_at DESC,id DESC);
