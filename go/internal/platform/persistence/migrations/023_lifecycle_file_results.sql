CREATE TABLE lifecycle_file_reports (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES lifecycle_runs(task_id),attempt INTEGER NOT NULL CHECK(attempt>0),
 operation TEXT NOT NULL CHECK(operation IN('trash','restore','purge')),total_files INTEGER NOT NULL CHECK(total_files BETWEEN 0 AND 100000),
 complete INTEGER NOT NULL DEFAULT 0 CHECK(complete IN(0,1))
);
CREATE TABLE lifecycle_file_results (
 task_id TEXT NOT NULL REFERENCES lifecycle_file_reports(task_id),position INTEGER NOT NULL CHECK(position BETWEEN 1 AND 100000),
 entry_id TEXT NOT NULL REFERENCES lifecycle_entries(id),state TEXT NOT NULL CHECK(state IN('applied','initially_missing','failed')),
 result_json TEXT NOT NULL CHECK(json_valid(result_json)),PRIMARY KEY(task_id,position)
);
CREATE INDEX lifecycle_file_results_state ON lifecycle_file_results(task_id,state);
