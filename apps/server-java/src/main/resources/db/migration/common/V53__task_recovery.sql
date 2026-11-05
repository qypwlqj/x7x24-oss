-- Recovery checkpoints belong to the same workspace as their task. History is retained.
CREATE TABLE task_recovery (
 id VARCHAR(80) PRIMARY KEY, task_id VARCHAR(80) NOT NULL, workspace_id VARCHAR(40) NOT NULL,
 task_version INTEGER NOT NULL, code VARCHAR(100) NOT NULL, detail TEXT NOT NULL,
 checkpoint TEXT NOT NULL, state VARCHAR(24) NOT NULL, created_at VARCHAR(40) NOT NULL,
 resumed_at VARCHAR(40), resolved_at VARCHAR(40), actor VARCHAR(100),
 UNIQUE(task_id, task_version), FOREIGN KEY(task_id) REFERENCES task(id)
);
CREATE INDEX ix_task_recovery ON task_recovery(workspace_id,task_id,state);
CREATE TABLE task_step_checkpoint (
 id VARCHAR(200) PRIMARY KEY, task_id VARCHAR(80) NOT NULL, workspace_id VARCHAR(40) NOT NULL,
 body TEXT NOT NULL, updated_at VARCHAR(40) NOT NULL, FOREIGN KEY(task_id) REFERENCES task(id)
);
