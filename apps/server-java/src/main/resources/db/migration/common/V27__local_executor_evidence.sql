ALTER TABLE coding_job ADD COLUMN result_digest VARCHAR(64);
ALTER TABLE coding_job ADD CONSTRAINT ux_coding_job_scope UNIQUE(workspace_id,id);
ALTER TABLE coding_job_event ADD CONSTRAINT fk_coding_event_scope FOREIGN KEY(workspace_id,job_id) REFERENCES coding_job(workspace_id,id);
ALTER TABLE node_run ADD COLUMN repository_resource_id VARCHAR(120);
CREATE TABLE skill_evaluation (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, task_id VARCHAR(80) NOT NULL, node_run_id VARCHAR(80) NOT NULL,
 set_digest VARCHAR(64) NOT NULL, body TEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY(workspace_id,task_id,node_run_id) REFERENCES node_run(workspace_id,task_id,id)
);
