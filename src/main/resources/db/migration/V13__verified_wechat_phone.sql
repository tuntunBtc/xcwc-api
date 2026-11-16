CREATE TABLE user_verified_phone (
  user_id VARCHAR(32) PRIMARY KEY,
  phone_e164 VARCHAR(16) NOT NULL,
  verified_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_verified_phone_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);
