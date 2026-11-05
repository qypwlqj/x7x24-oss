-- 需求对话里由 agent 提议、用户在确认卡上确认后执行的操作（新增产品线 / 项目、邀请成员）。
-- params 是服务端校验、规范化后的参数；result 不保存邀请令牌等敏感值。
CREATE TABLE chat_action (
  id VARCHAR(40) PRIMARY KEY,
  session_id VARCHAR(40) NOT NULL,
  workspace_id VARCHAR(40) NOT NULL,
  kind VARCHAR(40) NOT NULL,
  params TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  result TEXT,
  error TEXT,
  proposed_by VARCHAR(20) NOT NULL,
  decided_by VARCHAR(200),
  created_at VARCHAR(40) NOT NULL,
  decided_at VARCHAR(40)
);
CREATE INDEX ix_chat_action ON chat_action(session_id, created_at);
