-- 指挥台 D3：会话记住当前产品线（chat_session 已有行级安全，不需要新策略）
ALTER TABLE chat_session ADD COLUMN product_line_id VARCHAR(64);
