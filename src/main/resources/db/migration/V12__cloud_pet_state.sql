ALTER TABLE app_user ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN cloud_pet_enabled BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE pet_state (
 binding_id VARCHAR(32) PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 satiety INT NOT NULL DEFAULT 60 CHECK (satiety BETWEEN 0 AND 100),
 hydration INT NOT NULL DEFAULT 60 CHECK (hydration BETWEEN 0 AND 100),
 mood INT NOT NULL DEFAULT 80 CHECK (mood BETWEEN 20 AND 100),
 food INT NOT NULL DEFAULT 3 CHECK (food >= 0),
 water INT NOT NULL DEFAULT 3 CHECK (water >= 0),
 body_at TIMESTAMP NOT NULL,
 mood_at TIMESTAMP NOT NULL,
 companion_at TIMESTAMP NOT NULL,
 poop_due_at TIMESTAMP NULL,
 mood_date DATE NULL,
 first_companion BOOLEAN NOT NULL DEFAULT FALSE,
 pet_count INT NOT NULL DEFAULT 0,
 feed_count INT NOT NULL DEFAULT 0,
 water_count INT NOT NULL DEFAULT 0,
 clean_count INT NOT NULL DEFAULT 0,
 last_mood_pet_at TIMESTAMP NULL,
 FOREIGN KEY(binding_id) REFERENCES bird_binding(id) ON DELETE CASCADE
);
CREATE TABLE pet_operation (
 user_id VARCHAR(32) NOT NULL,
 request_id VARBINARY(64) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 result_json LONGTEXT NOT NULL,
 created_at TIMESTAMP NOT NULL,
 PRIMARY KEY(user_id,request_id),
 FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE TABLE pet_inventory_ledger (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 binding_id VARCHAR(32) NOT NULL,
 request_id VARBINARY(64) NOT NULL,
 operation VARCHAR(16) NOT NULL,
 food_delta INT NOT NULL,
 water_delta INT NOT NULL,
 food_after INT NOT NULL CHECK(food_after >= 0),
 water_after INT NOT NULL CHECK(water_after >= 0),
 created_at TIMESTAMP NOT NULL,
 UNIQUE(binding_id,request_id),
 FOREIGN KEY(binding_id) REFERENCES bird_binding(id) ON DELETE CASCADE
);
CREATE TABLE pet_supply_claim (
 user_id VARCHAR(32) NOT NULL,
 claim_date DATE NOT NULL,
 binding_id VARCHAR(32) NOT NULL,
 request_id VARBINARY(64) NOT NULL,
 created_at TIMESTAMP NOT NULL,
 PRIMARY KEY(user_id,claim_date),
 FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE,
 FOREIGN KEY(binding_id) REFERENCES bird_binding(id) ON DELETE CASCADE
);
