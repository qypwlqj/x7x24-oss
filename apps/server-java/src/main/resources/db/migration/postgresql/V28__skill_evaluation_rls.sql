ALTER TABLE skill_evaluation ENABLE ROW LEVEL SECURITY;
ALTER TABLE skill_evaluation FORCE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation ON skill_evaluation USING (x7_ws() = '' OR workspace_id = x7_ws()) WITH CHECK (x7_ws() = '' OR workspace_id = x7_ws());
CREATE TRIGGER skill_evaluation_no_mutation BEFORE UPDATE OR DELETE ON skill_evaluation FOR EACH ROW EXECUTE FUNCTION x7x24_forbid_mutation();
