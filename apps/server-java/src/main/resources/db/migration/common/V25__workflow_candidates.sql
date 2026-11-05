-- Immutable candidate snapshots and durable local execution/context facts; legacy routes are unchanged.
CREATE TABLE workflow_candidate (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, project_id VARCHAR(80) NOT NULL, task_id VARCHAR(80) NOT NULL,
 parent_candidate_id VARCHAR(80), request_key VARCHAR(100) NOT NULL, request_digest VARCHAR(64) NOT NULL,
 expected_task_version INTEGER NOT NULL, body TEXT NOT NULL, provenance VARCHAR(80) NOT NULL, created_at VARCHAR(40) NOT NULL,
 UNIQUE(workspace_id,task_id,request_key),
 FOREIGN KEY(workspace_id,project_id,task_id) REFERENCES task(workspace_id,project_id,id)
);
ALTER TABLE node_run ADD COLUMN result_manifest TEXT;
ALTER TABLE node_run ADD COLUMN executor_job_id VARCHAR(80);
ALTER TABLE node_run ADD COLUMN executor_checkpoint_ref TEXT;
ALTER TABLE node_run ADD CONSTRAINT ux_node_run_scope UNIQUE(workspace_id,task_id,id);
CREATE TABLE coding_job (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, task_id VARCHAR(80) NOT NULL,
 node_run_id VARCHAR(80) NOT NULL, operation_key VARCHAR(120) NOT NULL, request_digest VARCHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL, spec TEXT NOT NULL, result TEXT, checkpoint_ref TEXT, event_seq INTEGER NOT NULL DEFAULT 0,
 created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL,
 UNIQUE(workspace_id,task_id,operation_key), FOREIGN KEY(workspace_id,task_id,node_run_id) REFERENCES node_run(workspace_id,task_id,id)
);
CREATE TABLE coding_job_event (
 job_id VARCHAR(80) NOT NULL REFERENCES coding_job(id), seq INTEGER NOT NULL, workspace_id VARCHAR(40) NOT NULL,
 event_type VARCHAR(40) NOT NULL, body TEXT NOT NULL, created_at VARCHAR(40) NOT NULL, PRIMARY KEY(job_id,seq)
);
CREATE TABLE context_manifest (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, task_id VARCHAR(80) NOT NULL, node_run_id VARCHAR(80) NOT NULL,
 digest VARCHAR(64) NOT NULL, body TEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY(workspace_id,task_id,node_run_id) REFERENCES node_run(workspace_id,task_id,id)
);
