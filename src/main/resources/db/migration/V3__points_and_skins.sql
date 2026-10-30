ALTER TABLE app_user ADD COLUMN points_balance BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN pet_reward_date DATE NULL;
ALTER TABLE app_user ADD COLUMN pet_reward_count INT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN last_pet_reward_at TIMESTAMP NULL;
ALTER TABLE app_user ADD CONSTRAINT points_nonnegative CHECK (points_balance >= 0);
CREATE TABLE skin_catalog (
  id VARCHAR(32) PRIMARY KEY,
  name VARCHAR(48) NOT NULL,
  price INT NOT NULL,
  visual_key VARCHAR(32) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CHECK (price > 0)
);
INSERT INTO skin_catalog(id,name,price,visual_key) VALUES
('lake_glow','湖蓝光晕',30,'lake'),('sunset_glow','落日光晕',70,'sunset');
CREATE TABLE user_skin (
  user_id VARCHAR(32) NOT NULL,
  skin_id VARCHAR(32) NOT NULL,
  unlocked_at TIMESTAMP NOT NULL,
  PRIMARY KEY(user_id,skin_id),
  FOREIGN KEY(user_id) REFERENCES app_user(id),
  FOREIGN KEY(skin_id) REFERENCES skin_catalog(id)
);
CREATE TABLE points_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(32) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  operation VARCHAR(16) NOT NULL,
  target_id VARCHAR(32) NOT NULL,
  delta INT NOT NULL,
  balance_after BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  UNIQUE(user_id,request_id),
  FOREIGN KEY(user_id) REFERENCES app_user(id),
  CHECK (balance_after >= 0)
);
CREATE INDEX points_history_idx ON points_ledger(user_id,id);
ALTER TABLE bird ADD COLUMN equipped_skin_id VARCHAR(32) NULL;
ALTER TABLE bird ADD CONSTRAINT equipped_skin_fk FOREIGN KEY(equipped_skin_id) REFERENCES skin_catalog(id);
