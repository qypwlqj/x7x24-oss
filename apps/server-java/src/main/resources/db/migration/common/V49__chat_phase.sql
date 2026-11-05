-- 会话阶段：setup（先确定产品线 / 项目，草稿只暂存不展示）→ drafting（整理需求，展示草稿面板）；只进不退
ALTER TABLE chat_session ADD COLUMN phase VARCHAR(16) DEFAULT 'setup' NOT NULL;
UPDATE chat_session SET phase = 'drafting' WHERE project_id IS NOT NULL OR task_id IS NOT NULL OR anchor_kind IN ('project', 'task');
