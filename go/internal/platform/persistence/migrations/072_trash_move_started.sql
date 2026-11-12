ALTER TABLE lifecycle_runs ADD COLUMN trash_move_started INTEGER NOT NULL DEFAULT 0 CHECK(trash_move_started IN (0,1));
-- 已受理的旧回收可能已经开始搬移，缺少执行证据时只能沿用清单续跑。
UPDATE lifecycle_runs SET trash_move_started=1 WHERE operation='trash' AND completed=0;
