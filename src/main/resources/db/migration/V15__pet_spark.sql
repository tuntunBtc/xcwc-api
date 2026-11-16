CREATE TABLE pet_spark (
 binding_id VARCHAR(32) PRIMARY KEY,
 level BIGINT NOT NULL CHECK (level > 0),
 affinity BIGINT NOT NULL,
 normal_date DATE NOT NULL,
 recovery_last_date DATE NULL,
 recovery_progress INT NOT NULL CHECK (recovery_progress BETWEEN 0 AND 2),
 CONSTRAINT chk_pet_spark_affinity CHECK (affinity >= level),
 CONSTRAINT chk_pet_spark_recovery CHECK (
     (recovery_progress = 0 AND recovery_last_date IS NULL) OR
     (recovery_progress > 0 AND recovery_last_date IS NOT NULL)),
 FOREIGN KEY(binding_id) REFERENCES bird_binding(id) ON DELETE CASCADE
);

CREATE TABLE pet_spark_day (
 binding_id VARCHAR(32) NOT NULL,
 business_date DATE NOT NULL,
 request_id VARBINARY(64) NOT NULL,
 PRIMARY KEY(binding_id,business_date),
 FOREIGN KEY(binding_id) REFERENCES pet_spark(binding_id) ON DELETE CASCADE
);
