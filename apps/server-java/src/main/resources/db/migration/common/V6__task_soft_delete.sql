-- 软删除（归档）：已废弃任务可被操作员删除，列表 / 看板 / 查重中隐藏，记录与审计保留
ALTER TABLE task ADD COLUMN deleted_at VARCHAR(40);
