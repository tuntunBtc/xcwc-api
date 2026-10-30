CREATE TABLE app_user (
  id VARCHAR(32) PRIMARY KEY,
  username VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE TABLE auth_session (
  token_hash CHAR(64) PRIMARY KEY,
  user_id VARCHAR(32) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE INDEX session_expiry_idx ON auth_session(expires_at);
CREATE TABLE bird (
  id VARCHAR(32) PRIMARY KEY,
  type_id VARCHAR(32) NOT NULL,
  name VARCHAR(48) NOT NULL,
  claim_hash CHAR(64) NOT NULL,
  owner_id VARCHAR(32),
  claimed_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL,
  FOREIGN KEY (owner_id) REFERENCES app_user(id)
);
CREATE INDEX bird_owner_idx ON bird(owner_id);
