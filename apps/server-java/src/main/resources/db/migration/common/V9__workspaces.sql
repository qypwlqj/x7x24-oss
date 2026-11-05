-- 多工作区 P1：工作区与成员；租户根实体（产品线 / 项目 / 任务 / 需求信号）加 workspace_id。
-- Fresh snapshot: legacy rows are unclaimed; no username receives membership in SQL.
CREATE TABLE workspace (
  id VARCHAR(40) PRIMARY KEY,
  slug VARCHAR(60) NOT NULL UNIQUE,
  name VARCHAR(200) NOT NULL,
  kind VARCHAR(10) NOT NULL,
  owner_user_id VARCHAR(40),
  plan_quota TEXT NOT NULL DEFAULT '{}',
  status VARCHAR(10) NOT NULL DEFAULT 'active',
  created_at VARCHAR(40) NOT NULL
);
CREATE TABLE workspace_member (
  workspace_id VARCHAR(40) NOT NULL REFERENCES workspace(id),
  user_id VARCHAR(40) NOT NULL REFERENCES app_user(id),
  role VARCHAR(10) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX ix_member_user ON workspace_member(user_id);

INSERT INTO workspace (id, slug, name, kind, owner_user_id, plan_quota, status, created_at)
  VALUES ('ws_legacy', 'legacy-unclaimed', 'Unclaimed legacy workspace', 'personal', NULL, '{}', 'active', '2026-01-01T00:00:00.000Z');

ALTER TABLE product_line ADD COLUMN workspace_id VARCHAR(40);
UPDATE product_line SET workspace_id = 'ws_legacy';
ALTER TABLE product_line ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX ix_line_ws ON product_line(workspace_id);

ALTER TABLE project ADD COLUMN workspace_id VARCHAR(40);
UPDATE project SET workspace_id = 'ws_legacy';
ALTER TABLE project ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX ix_project_ws ON project(workspace_id);

ALTER TABLE task ADD COLUMN workspace_id VARCHAR(40);
UPDATE task SET workspace_id = 'ws_legacy';
ALTER TABLE task ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX ix_task_ws ON task(workspace_id, status);

ALTER TABLE signal ADD COLUMN workspace_id VARCHAR(40);
UPDATE signal SET workspace_id = 'ws_legacy';
ALTER TABLE signal ALTER COLUMN workspace_id SET NOT NULL;
