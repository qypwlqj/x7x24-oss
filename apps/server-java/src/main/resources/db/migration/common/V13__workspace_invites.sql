-- 多工作区 P5：邀请。只存令牌的 SHA-256，明文令牌只在创建时返回一次。
CREATE TABLE workspace_invite (
  id VARCHAR(40) PRIMARY KEY,
  workspace_id VARCHAR(40) NOT NULL REFERENCES workspace(id),
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  role VARCHAR(20) NOT NULL,
  login VARCHAR(200),
  created_by VARCHAR(40),
  created_at VARCHAR(40) NOT NULL,
  expires_at VARCHAR(40) NOT NULL,
  accepted_by VARCHAR(40),
  accepted_at VARCHAR(40),
  revoked_at VARCHAR(40)
);
CREATE INDEX ix_invite_ws ON workspace_invite(workspace_id);
