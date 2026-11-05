-- 指挥台 v2 M4：运行明细写进 task_event（AGENT_TOOLS / AGENT_BROWSER_CHECK / AGENT_NOTE / AGENT_RESULT），不另建 agent_run_step 表。
-- 这里只补查询用的索引：验收页签按任务 + 工作区查验收记录；保留清理按时间扫。
-- 顺序：#9 的 V40–V43 → #12 的 V44 → #13 的 V45/V46 → 本迁移 V47。
CREATE INDEX ix_agent_job_ws_task ON agent_job(workspace_id, task_id, created_at);
CREATE INDEX ix_ui_acceptance_created ON ui_acceptance_run(created_at);
