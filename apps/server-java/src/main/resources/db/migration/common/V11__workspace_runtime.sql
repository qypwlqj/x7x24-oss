-- 多工作区 P3：运行时数据按工作区。审计事件、预算台账、熔断开关、适配器调用日志加 workspace_id。
-- task_event 是只追加表（PostgreSQL 有禁止 UPDATE 的触发器）：用 ADD COLUMN ... DEFAULT 回填（不触发行触发器），随后去掉默认值，新写入必须显式给出。
ALTER TABLE task_event ADD COLUMN workspace_id VARCHAR(40) DEFAULT 'ws_legacy' NOT NULL;
ALTER TABLE task_event ALTER COLUMN workspace_id DROP DEFAULT;
CREATE INDEX ix_event_ws ON task_event(workspace_id, id);

ALTER TABLE budget_ledger ADD COLUMN workspace_id VARCHAR(40);
UPDATE budget_ledger SET workspace_id = 'ws_legacy';
ALTER TABLE budget_ledger ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX ix_ledger_ws_day ON budget_ledger(workspace_id, day);

-- 熔断开关：_platform = 平台级（作用于所有工作区）
ALTER TABLE kill_switch ADD COLUMN workspace_id VARCHAR(40);
UPDATE kill_switch SET workspace_id = CASE WHEN source = 'auto' AND trigger_key = 'audit:chain' THEN '_platform' ELSE 'ws_legacy' END;
ALTER TABLE kill_switch ALTER COLUMN workspace_id SET NOT NULL;

ALTER TABLE adapter_call ADD COLUMN workspace_id VARCHAR(40);
UPDATE adapter_call SET workspace_id = 'ws_legacy';
ALTER TABLE adapter_call ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX ix_adapter_ws ON adapter_call(workspace_id, id);
