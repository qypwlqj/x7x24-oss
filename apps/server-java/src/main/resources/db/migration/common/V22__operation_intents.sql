-- Expand existing operation ledger. Historical results retain their meaning.
ALTER TABLE tool_ops ADD COLUMN state VARCHAR(32) DEFAULT 'SUCCEEDED' NOT NULL;
ALTER TABLE tool_ops ADD COLUMN request_digest VARCHAR(64);
ALTER TABLE tool_ops ADD COLUMN updated_at VARCHAR(40);
