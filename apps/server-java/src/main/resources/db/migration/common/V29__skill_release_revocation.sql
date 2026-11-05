CREATE TABLE skill_release_revocation (
 workspace_id VARCHAR(40) NOT NULL, skill_ref VARCHAR(120) NOT NULL, reason TEXT NOT NULL, actor VARCHAR(120) NOT NULL, created_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(workspace_id,skill_ref)
);
