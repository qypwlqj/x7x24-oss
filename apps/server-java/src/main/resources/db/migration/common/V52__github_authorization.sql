-- Personal login grants are separate from workspace connections. Workspace rows only
-- reference an owner's grant; signing in never replaces a team connection.
CREATE TABLE github_user_authorization (
  user_id VARCHAR(40) PRIMARY KEY REFERENCES app_user(id),
  ciphertext TEXT NOT NULL,
  refresh_ciphertext TEXT,
  expires_at VARCHAR(40),
  refresh_expires_at VARCHAR(40),
  updated_at VARCHAR(40) NOT NULL
);
ALTER TABLE integration_credentials ADD COLUMN user_id VARCHAR(40) REFERENCES app_user(id);
ALTER TABLE integration_credentials ADD COLUMN refresh_ciphertext TEXT;
ALTER TABLE integration_credentials ADD COLUMN refresh_expires_at VARCHAR(40);
ALTER TABLE integration_credentials ADD COLUMN connection_state VARCHAR(30) DEFAULT 'unchecked' NOT NULL;
ALTER TABLE integration_credentials ADD COLUMN checked_at VARCHAR(40);
-- Explicitly selected repositories only; listing installations does not claim them.
ALTER TABLE github_installation ADD COLUMN repositories TEXT DEFAULT '{}' NOT NULL;
