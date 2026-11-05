-- 邮箱按账号保存；订阅、事件与投递记录按工作区隔离。
CREATE TABLE user_notification_email (
  user_id VARCHAR(40) PRIMARY KEY REFERENCES app_user(id),
  email VARCHAR(320) NOT NULL,
  verified_at VARCHAR(40),
  code_hash VARCHAR(64),
  code_expires_at VARCHAR(40),
  code_attempts INT NOT NULL DEFAULT 0,
  code_sent_at VARCHAR(40),
  code_window_at VARCHAR(40),
  code_requests INT NOT NULL DEFAULT 0,
  updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE notification_preference (
  workspace_id VARCHAR(40) NOT NULL REFERENCES workspace(id),
  user_id VARCHAR(40) NOT NULL REFERENCES app_user(id),
  email_enabled INT NOT NULL DEFAULT 0,
  in_app_enabled INT NOT NULL DEFAULT 1,
  scope VARCHAR(12) NOT NULL DEFAULT 'workspace',
  project_ids TEXT NOT NULL DEFAULT '[]',
  events TEXT NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (workspace_id, user_id)
);
CREATE TABLE notification_event (
  id VARCHAR(200) PRIMARY KEY,
  workspace_id VARCHAR(40) NOT NULL,
  project_id VARCHAR(150),
  task_id VARCHAR(64) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  subject VARCHAR(500) NOT NULL,
  message TEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  materialized_at VARCHAR(40),
  lease_until VARCHAR(40)
);
CREATE INDEX ix_notification_event_pending ON notification_event(materialized_at, created_at);
CREATE TABLE notification_delivery (
  id VARCHAR(40) PRIMARY KEY,
  event_id VARCHAR(200),
  workspace_id VARCHAR(40) NOT NULL,
  user_id VARCHAR(40) NOT NULL REFERENCES app_user(id),
  project_id VARCHAR(150),
  task_id VARCHAR(64),
  kind VARCHAR(32) NOT NULL,
  purpose VARCHAR(12) NOT NULL DEFAULT 'TASK',
  subject VARCHAR(500) NOT NULL,
  message TEXT,
  body_cipher TEXT,
  code_hash VARCHAR(64),
  email VARCHAR(320),
  in_app INT NOT NULL DEFAULT 1,
  state VARCHAR(12) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at VARCHAR(40),
  lease_until VARCHAR(40),
  last_error VARCHAR(500),
  created_at VARCHAR(40) NOT NULL,
  sent_at VARCHAR(40),
  read_at VARCHAR(40),
  UNIQUE(event_id, user_id)
);
CREATE INDEX ix_notification_delivery_queue ON notification_delivery(state, next_attempt_at);
CREATE INDEX ix_notification_delivery_inbox ON notification_delivery(workspace_id, user_id, created_at);
