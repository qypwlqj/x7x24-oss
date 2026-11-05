-- 操作完成后的「下一步」提示：每个操作只续一次；会话记住最近一次输入建议（输入框 placeholder / 建议按钮）
ALTER TABLE chat_action ADD COLUMN followed_up_at VARCHAR(40);
ALTER TABLE chat_session ADD COLUMN suggestions TEXT;
