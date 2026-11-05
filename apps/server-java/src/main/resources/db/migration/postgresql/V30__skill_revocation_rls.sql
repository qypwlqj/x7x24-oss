ALTER TABLE skill_release_revocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE skill_release_revocation FORCE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation ON skill_release_revocation USING (x7_ws() = '' OR workspace_id = x7_ws()) WITH CHECK (x7_ws() = '' OR workspace_id = x7_ws());
CREATE TRIGGER skill_release_revocation_no_mutation BEFORE UPDATE OR DELETE ON skill_release_revocation FOR EACH ROW EXECUTE FUNCTION x7x24_forbid_mutation();
