-- 指挥台 D4：成员确认被拒时，卡片记住需要什么角色（admin / owner），留给有权限的人确认
ALTER TABLE chat_action ADD COLUMN blocked_role VARCHAR(20);
