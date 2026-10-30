CREATE TABLE checkin_work (
  id VARCHAR(32) PRIMARY KEY,
  user_id VARCHAR(32) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  bird_ids VARCHAR(256) NOT NULL,
  style_id VARCHAR(16) NOT NULL,
  label VARCHAR(80) NOT NULL,
  original_path VARCHAR(64) NOT NULL,
  result_path VARCHAR(64) NOT NULL,
  original_type VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  UNIQUE(user_id, request_id),
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE INDEX checkin_history_idx ON checkin_work(user_id, created_at, id);
