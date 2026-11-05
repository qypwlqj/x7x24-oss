ALTER TABLE product_line ADD COLUMN description TEXT NOT NULL DEFAULT '';
ALTER TABLE product_line ADD COLUMN owner VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE product_line ADD COLUMN state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
CREATE TABLE project (
  id VARCHAR(150) PRIMARY KEY, product_line_id VARCHAR(100) NOT NULL REFERENCES product_line(id),
  name VARCHAR(200) NOT NULL, description TEXT NOT NULL DEFAULT '', owner VARCHAR(200) NOT NULL DEFAULT '',
  state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', repo_binding VARCHAR(250) UNIQUE,
  config TEXT NOT NULL DEFAULT '{}', config_version INTEGER NOT NULL DEFAULT 1,
  created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_project_line ON project(product_line_id);
INSERT INTO project (id,product_line_id,name,config,created_at,updated_at)
  SELECT id || '-default',id,name,config,created_at,created_at FROM product_line;
ALTER TABLE task ADD COLUMN project_id VARCHAR(150) REFERENCES project(id);
UPDATE task SET project_id=product_line || '-default';
ALTER TABLE task ALTER COLUMN project_id SET NOT NULL;
ALTER TABLE task ADD COLUMN current_stage VARCHAR(20) NOT NULL DEFAULT 'BACKLOG';
ALTER TABLE task ADD COLUMN execution_state VARCHAR(20) NOT NULL DEFAULT 'WAITING';
ALTER TABLE task ADD COLUMN acceptance_criteria TEXT NOT NULL DEFAULT '';
ALTER TABLE task ADD COLUMN pipeline_version VARCHAR(30) NOT NULL DEFAULT 'v1';
UPDATE task SET current_stage=CASE WHEN status IN ('ANOMALY','DEGRADED') THEN COALESCE(anomaly_from,'PLANNING') ELSE status END,
  execution_state=CASE WHEN paused<>0 THEN 'PAUSED' WHEN status='ANOMALY' THEN 'FAILED' WHEN status='DEGRADED' THEN 'BLOCKED' WHEN status='DISCARDED' THEN 'DISCARDED' WHEN status='RELEASED' AND feedback_done<>0 THEN 'COMPLETED' ELSE 'WAITING' END;
CREATE INDEX ix_task_project_stage ON task(project_id,current_stage);
ALTER TABLE signal ADD COLUMN project_id VARCHAR(150) REFERENCES project(id);
UPDATE signal SET project_id=product_line || '-default';
ALTER TABLE signal ALTER COLUMN project_id SET NOT NULL;
ALTER TABLE signal DROP CONSTRAINT uq_signal_fp;
ALTER TABLE signal ADD CONSTRAINT uq_signal_project_fp UNIQUE(project_id,fingerprint);
CREATE UNIQUE INDEX uq_signal_project_ref ON signal(project_id,source_ref);
ALTER TABLE budget_ledger ADD COLUMN project_id VARCHAR(150) REFERENCES project(id);
UPDATE budget_ledger SET project_id=product_line || '-default' WHERE product_line IS NOT NULL;
CREATE INDEX ix_ledger_project_day ON budget_ledger(project_id,day);
ALTER TABLE adapter_call ADD COLUMN project_id VARCHAR(150) REFERENCES project(id);
UPDATE adapter_call SET project_id=(SELECT p.id FROM project p WHERE p.product_line_id=adapter_call.line);
CREATE INDEX ix_adapter_project ON adapter_call(project_id,id);
CREATE TABLE stage_execution (
  id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL REFERENCES task(id),
  iteration_seq INTEGER NOT NULL, stage VARCHAR(20) NOT NULL, attempt INTEGER NOT NULL,
  operation_key VARCHAR(300) NOT NULL UNIQUE, state VARCHAR(20) NOT NULL,
  input TEXT NOT NULL DEFAULT '{}', output TEXT NOT NULL DEFAULT '{}', error TEXT,
  tokens BIGINT NOT NULL DEFAULT 0, usd DOUBLE PRECISION NOT NULL DEFAULT 0,
  started_at VARCHAR(40) NOT NULL, ended_at VARCHAR(40)
);
CREATE INDEX ix_stage_task ON stage_execution(task_id,started_at);
CREATE TABLE discovery_candidate (
  id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(150) NOT NULL REFERENCES project(id),
  source VARCHAR(30) NOT NULL, source_ref VARCHAR(300), fingerprint VARCHAR(64) NOT NULL,
  title VARCHAR(500) NOT NULL, problem TEXT NOT NULL, acceptance_criteria TEXT NOT NULL,
  evidence TEXT NOT NULL, risk_level VARCHAR(20) NOT NULL DEFAULT 'mid',
  state VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE', task_id VARCHAR(64) REFERENCES task(id),
  occurrences INTEGER NOT NULL DEFAULT 1, created_at VARCHAR(40) NOT NULL, last_seen_at VARCHAR(40) NOT NULL,
  CONSTRAINT uq_candidate_project_fp UNIQUE(project_id,fingerprint)
);
CREATE TABLE discovery_scan (
  id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(150) NOT NULL REFERENCES project(id),
  state VARCHAR(20) NOT NULL, summary TEXT, started_at VARCHAR(40) NOT NULL, ended_at VARCHAR(40)
);
