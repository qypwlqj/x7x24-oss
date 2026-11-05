-- 可组合流程 P4：过程记录的节点 id 与节点执行记录一致放宽到 60（工作区派生节点 id 最长 41）。
ALTER TABLE evidence ALTER COLUMN stage SET DATA TYPE VARCHAR(60);
