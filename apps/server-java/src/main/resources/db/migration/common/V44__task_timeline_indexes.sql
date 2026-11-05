-- 指挥台 v2 · M2：任务时间线 / 事件流 / 节点详情页的查询索引。
-- 事件流的 seq 直接使用 task_event.id（只追加、全局单调、由 AuditLog 串行写入），不新增列：
-- task_event 有只追加触发器和哈希链，不能回填旧行。按任务续传走已有的 ix_event_task(task_id, id)。
-- 版本号从 V44 开始，避开 PR #9 的 V40–V43。
CREATE INDEX ix_evidence_task_stage ON evidence(task_id, stage, kind);
CREATE INDEX ix_event_task_type ON task_event(task_id, event_type, id);
