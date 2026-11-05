-- 账号体系：GitHub 登录用户。不保存用户的 GitHub 访问令牌。
CREATE TABLE app_user (
  id VARCHAR(40) PRIMARY KEY,
  github_id BIGINT NOT NULL UNIQUE,
  login VARCHAR(200) NOT NULL,
  name VARCHAR(200),
  avatar_url VARCHAR(500),
  created_at VARCHAR(40) NOT NULL,
  last_login_at VARCHAR(40) NOT NULL
);
