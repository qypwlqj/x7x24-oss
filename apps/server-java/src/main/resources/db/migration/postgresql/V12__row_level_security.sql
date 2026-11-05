-- 多工作区 P4（决策 8A）：Postgres 行级安全，作为应用层过滤之外的第二道防线。
-- 每次取连接时由 TenantDataSource 设置会话变量 x7.ws：
--   空串 = 系统 / 平台上下文（引擎调度、启动自检、审计哈希链）→ 不限制；
--   工作区 id = 只能读写该工作区的行（kill_switch 另外可读平台级 _platform 行）。
-- FORCE：表的属主（应用连接用的就是属主账号）也受策略约束。超级用户不受 RLS 约束，生产账号不是超级用户。
CREATE FUNCTION x7_ws() RETURNS text LANGUAGE sql STABLE AS $$ SELECT coalesce(current_setting('x7.ws', true), '') $$;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['product_line','project','task','signal','task_event','budget_ledger','adapter_call','github_installation'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY ws_isolation ON %I USING (x7_ws() = '''' OR workspace_id = x7_ws()) WITH CHECK (x7_ws() = '''' OR workspace_id = x7_ws())', t);
  END LOOP;
END $$;

ALTER TABLE kill_switch ENABLE ROW LEVEL SECURITY;
ALTER TABLE kill_switch FORCE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation ON kill_switch
  USING (x7_ws() = '' OR workspace_id = x7_ws() OR workspace_id = '_platform')
  WITH CHECK (x7_ws() = '' OR workspace_id = x7_ws() OR workspace_id = '_platform');
