-- Learning facts are separate from delivery state. Unknown evidence never implies success.
CREATE TABLE learning_episode (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, project_id VARCHAR(150) NOT NULL,
 task_id VARCHAR(64) NOT NULL, root_task_id VARCHAR(64) NOT NULL, source_kind VARCHAR(20) NOT NULL,
 source_id VARCHAR(80) NOT NULL, body TEXT NOT NULL, digest VARCHAR(64) NOT NULL, created_at VARCHAR(40) NOT NULL,
 UNIQUE(workspace_id,source_kind,source_id),
 FOREIGN KEY(workspace_id,project_id,task_id) REFERENCES task(workspace_id,project_id,id)
);
CREATE INDEX ix_learning_episode_project ON learning_episode(workspace_id,project_id,created_at);
CREATE TABLE learning_outcome (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, project_id VARCHAR(150) NOT NULL,
 task_id VARCHAR(64) NOT NULL, body TEXT NOT NULL, digest VARCHAR(64) NOT NULL, observed_at VARCHAR(40) NOT NULL,
 UNIQUE(workspace_id,task_id,digest), FOREIGN KEY(workspace_id,project_id,task_id) REFERENCES task(workspace_id,project_id,id)
);
CREATE TABLE learning_suite (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, project_id VARCHAR(150) NOT NULL,
 body TEXT NOT NULL, digest VARCHAR(64) NOT NULL, sealed_by VARCHAR(80), created_at VARCHAR(40) NOT NULL
);
CREATE TABLE learning_policy (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, project_id VARCHAR(150) NOT NULL,
 suite_id VARCHAR(80), parent_id VARCHAR(80), body TEXT NOT NULL, digest VARCHAR(64) NOT NULL, created_at VARCHAR(40) NOT NULL
);
CREATE TABLE learning_experience (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, project_id VARCHAR(150) NOT NULL,
 policy_id VARCHAR(80) NOT NULL, body TEXT NOT NULL, digest VARCHAR(64) NOT NULL, created_at VARCHAR(40) NOT NULL
);
CREATE TABLE learning_experiment (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, project_id VARCHAR(150) NOT NULL,
 policy_id VARCHAR(80) NOT NULL, suite_id VARCHAR(80) NOT NULL, split VARCHAR(16) NOT NULL,
 state VARCHAR(24) NOT NULL, body TEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
 UNIQUE(workspace_id,policy_id,suite_id,split)
);
CREATE TABLE learning_trial (
 id VARCHAR(80) PRIMARY KEY, experiment_id VARCHAR(80) NOT NULL REFERENCES learning_experiment(id),
 workspace_id VARCHAR(40) NOT NULL, project_id VARCHAR(150) NOT NULL,
 case_id VARCHAR(80) NOT NULL, arm VARCHAR(16) NOT NULL, body TEXT NOT NULL, digest VARCHAR(64) NOT NULL,
 created_at VARCHAR(40) NOT NULL, UNIQUE(experiment_id,case_id,arm)
);
CREATE TABLE learning_release (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, project_id VARCHAR(150) NOT NULL,
 policy_id VARCHAR(80) NOT NULL, state VARCHAR(24) NOT NULL, percentage INTEGER NOT NULL,
 reason TEXT NOT NULL, created_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_learning_release_project ON learning_release(workspace_id,project_id,created_at);
CREATE TABLE learning_assignment (
 workspace_id VARCHAR(40) NOT NULL, project_id VARCHAR(150) NOT NULL, task_id VARCHAR(64) NOT NULL,
 policy_id VARCHAR(80) NOT NULL, release_id VARCHAR(80), created_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(workspace_id,task_id), FOREIGN KEY(workspace_id,project_id,task_id) REFERENCES task(workspace_id,project_id,id)
);
CREATE TABLE learning_call (
 id VARCHAR(80) PRIMARY KEY, workspace_id VARCHAR(40) NOT NULL, project_id VARCHAR(150) NOT NULL,
 product_line VARCHAR(100) NOT NULL, state VARCHAR(24) NOT NULL, reserved_usd DOUBLE PRECISION NOT NULL, reserved_tokens BIGINT NOT NULL,
 actual_usd DOUBLE PRECISION NOT NULL DEFAULT 0, created_at VARCHAR(40) NOT NULL
);
