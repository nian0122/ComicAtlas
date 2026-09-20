CREATE TABLE IF NOT EXISTS ai_analysis_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_path VARCHAR(1000) NOT NULL,
  status VARCHAR(24) NOT NULL,
  progress INT NOT NULL DEFAULT 0,
  result_json JSON NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(1000) NULL,
  attempts INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  started_at TIMESTAMP(6) NULL,
  finished_at TIMESTAMP(6) NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  INDEX idx_ai_analysis_task_status_created (status, created_at)
);
