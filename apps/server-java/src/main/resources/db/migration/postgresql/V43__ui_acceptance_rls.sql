ALTER TABLE ui_acceptance_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE ui_acceptance_run FORCE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation ON ui_acceptance_run USING (EXISTS (SELECT 1 FROM agent_job j WHERE j.id = ui_acceptance_run.id)) WITH CHECK (EXISTS (SELECT 1 FROM agent_job j WHERE j.id = ui_acceptance_run.id));
