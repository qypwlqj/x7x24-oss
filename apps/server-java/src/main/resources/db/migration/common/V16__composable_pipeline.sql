-- 可组合流程 P3：任务当前所在的流程节点；节点执行记录的节点 id 放宽到 60；工作区派生节点 / 预设（决策 D6 / D5）。
ALTER TABLE task ADD COLUMN current_node VARCHAR(60);
ALTER TABLE stage_execution ALTER COLUMN stage SET DATA TYPE VARCHAR(60);
CREATE TABLE pipeline_node (
  workspace_id VARCHAR(40) NOT NULL,
  id VARCHAR(60) NOT NULL,
  version INTEGER NOT NULL,
  body TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_by VARCHAR(80),
  reviewed_by VARCHAR(80),
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (workspace_id, id, version)
);
CREATE TABLE pipeline_preset (
  workspace_id VARCHAR(40) NOT NULL,
  id VARCHAR(60) NOT NULL,
  version INTEGER NOT NULL,
  body TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_by VARCHAR(80),
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (workspace_id, id, version)
);
CREATE INDEX ix_task_project_node ON task(project_id, current_node);
