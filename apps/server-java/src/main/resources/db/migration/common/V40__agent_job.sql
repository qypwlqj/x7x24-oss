-- 代码开发运行时（AGENT_RUNTIME=remote-docker）的 job 与日志。开关默认关闭时不会写入任何行。
-- 不保存任何令牌或密钥；files/untracked 只记数量与路径。
CREATE TABLE agent_job (
  id VARCHAR(40) PRIMARY KEY,
  workspace_id VARCHAR(40) NOT NULL,
  task_id VARCHAR(40) NOT NULL,
  node_run_id VARCHAR(200),
  runtime VARCHAR(40) NOT NULL,
  remote_id VARCHAR(80),
  state VARCHAR(20) NOT NULL,
  repo VARCHAR(200) NOT NULL,
  base_ref VARCHAR(200),
  mode VARCHAR(20) NOT NULL,
  cancel_requested INTEGER NOT NULL DEFAULT 0,
  head_sha VARCHAR(80),
  files INTEGER NOT NULL DEFAULT 0,
  untracked TEXT,
  tokens_in BIGINT NOT NULL DEFAULT 0,
  tokens_out BIGINT NOT NULL DEFAULT 0,
  tokens_cache BIGINT NOT NULL DEFAULT 0,
  turns INTEGER NOT NULL DEFAULT 0,
  usd DOUBLE PRECISION NOT NULL DEFAULT 0,
  error TEXT,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_agent_job_task ON agent_job(task_id, created_at);
CREATE TABLE agent_job_log (
  job_id VARCHAR(40) NOT NULL REFERENCES agent_job(id),
  seq BIGINT NOT NULL,
  at VARCHAR(40),
  kind VARCHAR(20) NOT NULL,
  text TEXT NOT NULL,
  PRIMARY KEY (job_id, seq)
);
