-- 页面验收结果（UI_ACCEPTANCE）。id 与 agent_job.id 相同；截图存于 DATA_DIR/ui-acceptance/<id>/，不入库。
CREATE TABLE ui_acceptance_run (
  id VARCHAR(40) PRIMARY KEY REFERENCES agent_job(id),
  task_id VARCHAR(40) NOT NULL,
  verdict VARCHAR(20) NOT NULL,
  blocked INTEGER NOT NULL DEFAULT 0,
  report TEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_ui_acceptance_task ON ui_acceptance_run(task_id, created_at);
