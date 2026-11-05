-- 多工作区 P2：GitHub App 安装与工作区一对一绑定。
-- 遗留工作区（ws_legacy）沿用原来的全局配置行（治理 / 流程 / 模型 / GitHub 凭据 / 运行模式），不迁移数据；其他工作区用 ws:<id>:* 键。
CREATE TABLE github_installation (
  installation_id BIGINT PRIMARY KEY,
  workspace_id VARCHAR(40) NOT NULL REFERENCES workspace(id),
  account_login VARCHAR(200),
  verified_by_user VARCHAR(40) NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
