-- 指挥台 v2 M3：在会话里控制流程的动作（提议 → 确认 → 执行）。
-- 与 chat_action 分开存：按钮动作不一定有会话，且要按任务做「同一时间只有一个待确认动作」和乐观锁。
-- 编号从 V45 起，避开 #9 的 V40–V43；V44 在 #12。
CREATE TABLE task_action (
  id VARCHAR(40) PRIMARY KEY,
  workspace_id VARCHAR(40) NOT NULL,
  task_id VARCHAR(64) NOT NULL,
  session_id VARCHAR(40),
  kind VARCHAR(40) NOT NULL,
  params TEXT NOT NULL,
  danger VARCHAR(10) NOT NULL,
  min_role VARCHAR(20) NOT NULL,
  expected_key VARCHAR(200) NOT NULL,
  source VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  proposed_by VARCHAR(200) NOT NULL,
  blocked_role VARCHAR(20),
  result TEXT,
  error VARCHAR(600),
  decided_by VARCHAR(200),
  created_at VARCHAR(40) NOT NULL,
  decided_at VARCHAR(40)
);
CREATE INDEX ix_task_action_task ON task_action(task_id, status);
CREATE INDEX ix_task_action_ws ON task_action(workspace_id, status, created_at);
CREATE INDEX ix_chat_session_task ON chat_session(task_id);
