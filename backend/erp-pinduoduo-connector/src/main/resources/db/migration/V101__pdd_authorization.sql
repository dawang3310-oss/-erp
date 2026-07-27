CREATE TABLE pdd_authorization (
  id VARCHAR(26) PRIMARY KEY,
  shop_id VARCHAR(26) NOT NULL,
  scopes_json JSON NOT NULL,
  access_token_ciphertext TEXT NOT NULL,
  refresh_token_ciphertext TEXT NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  status VARCHAR(24) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  UNIQUE (shop_id)
);

CREATE TABLE pdd_audit_log (
  id VARCHAR(26) PRIMARY KEY,
  shop_id VARCHAR(26) NOT NULL,
  action VARCHAR(64) NOT NULL,
  business_key VARCHAR(160) NOT NULL,
  request_id VARCHAR(64),
  result_summary VARCHAR(512) NOT NULL,
  operator_id VARCHAR(64) NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL
);
