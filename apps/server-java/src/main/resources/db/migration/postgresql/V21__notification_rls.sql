DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['notification_preference','notification_event','notification_delivery'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY ws_isolation ON %I USING (x7_ws() = '''' OR workspace_id = x7_ws()) WITH CHECK (x7_ws() = '''' OR workspace_id = x7_ws())', t);
  END LOOP;
END $$;
