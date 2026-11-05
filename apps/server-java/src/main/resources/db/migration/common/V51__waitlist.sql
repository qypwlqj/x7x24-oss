-- 首页等待名单：只存邮箱、同意时间与来源；不存 IP。平台级数据（不属于任何工作区）
CREATE TABLE waitlist (
  id VARCHAR(40) PRIMARY KEY,
  email VARCHAR(254) NOT NULL,
  source VARCHAR(40),
  consent_at VARCHAR(40) NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
CREATE UNIQUE INDEX ux_waitlist_email ON waitlist(email);
