CREATE TABLE pet_diary_day (
 binding_id VARCHAR(32) NOT NULL,
 diary_date DATE NOT NULL,
 mood VARCHAR(12) NOT NULL,
 title VARCHAR(120) NOT NULL,
 diary_text VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY (binding_id, diary_date),
 FOREIGN KEY (binding_id) REFERENCES bird_binding(id) ON DELETE CASCADE
);

CREATE TABLE pet_diary_activity (
 binding_id VARCHAR(32) NOT NULL,
 diary_date DATE NOT NULL,
 source_id VARBINARY(64) NOT NULL,
 kind VARCHAR(8) NOT NULL CHECK (kind IN ('travel', 'study')),
 title VARCHAR(120) NOT NULL,
 activity_text VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY (binding_id, source_id),
 FOREIGN KEY (binding_id, diary_date) REFERENCES pet_diary_day(binding_id, diary_date) ON DELETE CASCADE
);
CREATE INDEX ix_diary_activity_day ON pet_diary_activity(binding_id, diary_date, created_at);

CREATE TABLE pet_diary_letter (
 id VARCHAR(32) PRIMARY KEY,
 binding_id VARCHAR(32) NOT NULL,
 diary_date DATE NOT NULL,
 request_id VARBINARY(64) NOT NULL,
 letter_text VARCHAR(500) NOT NULL,
 reply VARCHAR(800) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 read_at TIMESTAMP(6),
 UNIQUE (binding_id, request_id),
 FOREIGN KEY (binding_id, diary_date) REFERENCES pet_diary_day(binding_id, diary_date) ON DELETE CASCADE
);
CREATE INDEX ix_diary_letter_day ON pet_diary_letter(binding_id, diary_date, created_at);
