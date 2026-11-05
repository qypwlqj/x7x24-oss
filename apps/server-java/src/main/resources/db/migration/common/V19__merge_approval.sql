-- 合并关卡：大改动 / 高风险合并前的负责人确认，绑定 PR 头提交（新提交 → 旧确认作废）
CREATE TABLE merge_approval (
  id VARCHAR(64) PRIMARY KEY,
  task_id VARCHAR(64) NOT NULL REFERENCES task(id),
  tier VARCHAR(4) NOT NULL,
  head_sha VARCHAR(64) NOT NULL,
  summary TEXT NOT NULL,
  status VARCHAR(12) NOT NULL,
  decided_by VARCHAR(100),
  decided_at VARCHAR(40),
  note TEXT,
  reminded_at VARCHAR(40),
  created_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_merge_approval_task ON merge_approval(task_id, head_sha);
