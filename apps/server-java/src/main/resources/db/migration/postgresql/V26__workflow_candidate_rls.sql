DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['workflow_candidate','coding_job','coding_job_event','context_manifest'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY ws_isolation ON %I USING (x7_ws() = '''' OR workspace_id = x7_ws()) WITH CHECK (x7_ws() = '''' OR workspace_id = x7_ws())',t);
 END LOOP;
END $$;
CREATE TRIGGER workflow_candidate_no_mutation BEFORE UPDATE OR DELETE ON workflow_candidate FOR EACH ROW EXECUTE FUNCTION x7x24_forbid_mutation();
CREATE TRIGGER coding_job_event_no_mutation BEFORE UPDATE OR DELETE ON coding_job_event FOR EACH ROW EXECUTE FUNCTION x7x24_forbid_mutation();
CREATE TRIGGER context_manifest_no_mutation BEFORE UPDATE OR DELETE ON context_manifest FOR EACH ROW EXECUTE FUNCTION x7x24_forbid_mutation();
