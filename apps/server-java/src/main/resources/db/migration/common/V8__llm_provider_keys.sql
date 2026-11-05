-- 模型供应商 API Key：只存密文（AES-GCM，与 GitHub 凭据同一主密钥 X7_SECRET_KEY）+ 末 4 位与时间。明文永不落库、永不返回前端。
CREATE TABLE llm_provider_key (
  id VARCHAR(80) PRIMARY KEY,
  ciphertext TEXT NOT NULL,
  last4 VARCHAR(8) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);
