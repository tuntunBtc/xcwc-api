-- One receipt per WeChat account and Beijing date. No steps or raw installation IDs are kept.
CREATE TABLE wx_supply_claim (
  user_id VARCHAR(32) NOT NULL,
  claim_date DATE NOT NULL,
  receipt_id CHAR(32) NOT NULL UNIQUE,
  installation_hash CHAR(64) NOT NULL,
  bird_code VARCHAR(32) NOT NULL,
  PRIMARY KEY (user_id, claim_date),
  FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);
