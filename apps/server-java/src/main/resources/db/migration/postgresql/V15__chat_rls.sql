-- 对话表纳入行级安全（与 V12 相同的策略）
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['chat_session','chat_message','chat_draft'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY ws_isolation ON %I USING (x7_ws() = '''' OR workspace_id = x7_ws()) WITH CHECK (x7_ws() = '''' OR workspace_id = x7_ws())', t);
  END LOOP;
END $$;
