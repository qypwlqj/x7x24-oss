-- chat_action 纳入行级安全（与 V15 相同的策略）
ALTER TABLE chat_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_action FORCE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation ON chat_action USING (x7_ws() = '' OR workspace_id = x7_ws()) WITH CHECK (x7_ws() = '' OR workspace_id = x7_ws());
