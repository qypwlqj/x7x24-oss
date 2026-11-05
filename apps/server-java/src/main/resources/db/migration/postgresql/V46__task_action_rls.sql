-- task_action 纳入行级安全（与 V32 相同的策略）
ALTER TABLE task_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_action FORCE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation ON task_action USING (x7_ws() = '' OR workspace_id = x7_ws()) WITH CHECK (x7_ws() = '' OR workspace_id = x7_ws());
