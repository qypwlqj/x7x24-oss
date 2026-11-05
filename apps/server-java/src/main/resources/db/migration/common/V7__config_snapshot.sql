-- 流程 / 角色 / 模型配置的生效快照（内容寻址，不可变）。任务在 context.configSnapshot 里钉住 id。
CREATE TABLE config_snapshot (
  id VARCHAR(40) PRIMARY KEY,
  line_id VARCHAR(100),
  body TEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
