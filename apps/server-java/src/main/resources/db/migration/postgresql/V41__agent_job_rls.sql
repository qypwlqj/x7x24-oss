-- agent_job 纳入行级安全（与 V15 相同的策略）；日志经 job 归属工作区。
ALTER TABLE agent_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_job FORCE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation ON agent_job USING (x7_ws() = '' OR workspace_id = x7_ws()) WITH CHECK (x7_ws() = '' OR workspace_id = x7_ws());
ALTER TABLE agent_job_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_job_log FORCE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation ON agent_job_log USING (EXISTS (SELECT 1 FROM agent_job j WHERE j.id = job_id)) WITH CHECK (EXISTS (SELECT 1 FROM agent_job j WHERE j.id = job_id));
