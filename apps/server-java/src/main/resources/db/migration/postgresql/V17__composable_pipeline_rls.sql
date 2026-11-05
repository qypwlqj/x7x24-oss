-- 工作区派生节点 / 预设纳入行级安全（与 V12 / V15 相同的策略）
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['pipeline_node','pipeline_preset'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY ws_isolation ON %I USING (x7_ws() = '''' OR workspace_id = x7_ws()) WITH CHECK (x7_ws() = '''' OR workspace_id = x7_ws())', t);
  END LOOP;
END $$;
