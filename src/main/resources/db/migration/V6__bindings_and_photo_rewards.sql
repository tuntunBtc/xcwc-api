CREATE TABLE bird_binding (
  id VARCHAR(32) PRIMARY KEY,
  user_id VARCHAR(32) NOT NULL,
  bird_id VARCHAR(32) NOT NULL,
  nickname VARCHAR(48) NOT NULL,
  adoption_name VARCHAR(48) NOT NULL,
  request_id VARCHAR(64),
  equipped_skin_id VARCHAR(32),
  created_at TIMESTAMP NOT NULL,
  hatched_at TIMESTAMP NOT NULL,
  UNIQUE(user_id,bird_id),
  UNIQUE(user_id,request_id),
  FOREIGN KEY(user_id) REFERENCES app_user(id),
  FOREIGN KEY(bird_id) REFERENCES bird(id),
  FOREIGN KEY(equipped_skin_id) REFERENCES skin_catalog(id)
);
INSERT INTO bird_binding(id,user_id,bird_id,nickname,adoption_name,equipped_skin_id,created_at,hatched_at)
SELECT id,owner_id,id,name,name,equipped_skin_id,COALESCE(claimed_at,created_at),COALESCE(claimed_at,created_at) FROM bird WHERE owner_id IS NOT NULL;
ALTER TABLE app_user ADD COLUMN initial_reward_received BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE app_user SET initial_reward_received=TRUE WHERE id IN (SELECT user_id FROM bird_binding);
ALTER TABLE checkin_work ADD COLUMN source_hash CHAR(64) NULL;
ALTER TABLE checkin_work ADD COLUMN reward_amount INT NOT NULL DEFAULT 1;
CREATE TABLE checkin_reward_source (
  user_id VARCHAR(32) NOT NULL,
  source_hash CHAR(64) NOT NULL,
  PRIMARY KEY(user_id,source_hash),
  FOREIGN KEY(user_id) REFERENCES app_user(id)
);
CREATE TABLE checkin_reward_day (
  user_id VARCHAR(32) NOT NULL,
  reward_date DATE NOT NULL,
  rewarded INT NOT NULL,
  PRIMARY KEY(user_id,reward_date),
  FOREIGN KEY(user_id) REFERENCES app_user(id)
);
-- Historical balances/awards remain untouched; service also counts old awarded rows per Shanghai day.
