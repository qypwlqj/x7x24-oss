-- 集成凭据：只存密文（AES-GCM）与元数据；令牌明文永不落库、永不返回前端。
CREATE TABLE integration_credentials (
  id VARCHAR(40) PRIMARY KEY,
  provider VARCHAR(20) NOT NULL,
  source VARCHAR(10) NOT NULL,
  github_login VARCHAR(200),
  avatar_url VARCHAR(500),
  scopes TEXT NOT NULL DEFAULT '',
  permissions_summary TEXT NOT NULL DEFAULT '',
  ciphertext TEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  expires_at VARCHAR(40)
);
