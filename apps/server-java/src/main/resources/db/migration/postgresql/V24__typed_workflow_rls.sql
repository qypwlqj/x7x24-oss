DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['workflow_plan_revision','node_instance','input_binding','control_dependency','artifact_version','artifact_lineage','artifact_validation','approval_record','artifact_invalidation','node_run','execution_lease'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
  EXECUTE format('CREATE POLICY ws_isolation ON %I USING (x7_ws() = '''' OR workspace_id = x7_ws()) WITH CHECK (x7_ws() = '''' OR workspace_id = x7_ws())',t);
 END LOOP;
END $$;

CREATE TRIGGER artifact_version_no_mutation BEFORE UPDATE OR DELETE ON artifact_version FOR EACH ROW EXECUTE FUNCTION x7x24_forbid_mutation();

CREATE TRIGGER artifact_lineage_no_mutation BEFORE UPDATE OR DELETE ON artifact_lineage FOR EACH ROW EXECUTE FUNCTION x7x24_forbid_mutation();

CREATE TRIGGER artifact_validation_no_mutation BEFORE UPDATE OR DELETE ON artifact_validation FOR EACH ROW EXECUTE FUNCTION x7x24_forbid_mutation();

CREATE TRIGGER artifact_invalidation_no_mutation BEFORE UPDATE OR DELETE ON artifact_invalidation FOR EACH ROW EXECUTE FUNCTION x7x24_forbid_mutation();
