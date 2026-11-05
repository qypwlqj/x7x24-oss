-- 对话归属（docs/command-center-v2-nav.md「最近对话」）：每个会话挂在最具体的对象上 task > project > line > none
-- chat_session 已有行级安全（V15），不需要新策略
ALTER TABLE chat_session ADD COLUMN anchor_kind VARCHAR(16);
ALTER TABLE chat_session ADD COLUMN anchor_id VARCHAR(150);
UPDATE chat_session SET anchor_kind = CASE WHEN task_id IS NOT NULL THEN 'task' WHEN project_id IS NOT NULL THEN 'project' WHEN product_line_id IS NOT NULL THEN 'line' ELSE 'none' END,
  anchor_id = CASE WHEN task_id IS NOT NULL THEN task_id WHEN project_id IS NOT NULL THEN project_id ELSE product_line_id END;
CREATE INDEX idx_chat_session_anchor ON chat_session(workspace_id, anchor_kind, anchor_id, updated_at);
