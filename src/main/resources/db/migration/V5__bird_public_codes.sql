CREATE TABLE bird_city_code (
  code VARCHAR(2) PRIMARY KEY,
  city_key VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(48) NOT NULL
);
INSERT INTO bird_city_code(code,city_key,name) VALUES ('HZ','hangzhou','杭州');
CREATE TABLE bird_ip_code (
  code VARCHAR(8) PRIMARY KEY,
  name VARCHAR(48) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO bird_ip_code(code,name,enabled) VALUES
('BTGJ','白头高洁',FALSE),('HH','荷花鸟',FALSE),('GH','桂花鸟',FALSE),
('DP','东坡鸟',FALSE),('YF','岳飞鸟',FALSE),('JL','剑来鸟',FALSE),
('LY','灵羽鸟',FALSE),('XD','小德鸟',FALSE),('TH','桃花鸟',FALSE),
('YNGG','玉鸟咕咕',FALSE),('HY','小黄莺',TRUE),('XY','星月鸟',FALSE),
('RY','如意小鸟',FALSE),('QJJ','戚家军鸟',FALSE),('HZXS','红嘴相思鸟',FALSE),
('SHXYW','宋画小鹦鹉',FALSE),('YL','园林鸟系列（待拆分）',FALSE),
('XXSBG','小学生白鸽',FALSE),('YY','鸳鸯鸟',FALSE);
ALTER TABLE bird ADD COLUMN bird_code VARCHAR(32) NULL;
ALTER TABLE bird ADD COLUMN ip_code VARCHAR(8) NULL;
ALTER TABLE bird ADD COLUMN code_city VARCHAR(2) NULL;
ALTER TABLE bird ADD COLUMN image_path VARCHAR(256) NOT NULL DEFAULT '/miniprogram/assets/bird_calm.png';
ALTER TABLE bird ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE bird ADD CONSTRAINT bird_code_unique UNIQUE(bird_code);
ALTER TABLE bird ADD CONSTRAINT bird_ip_fk FOREIGN KEY(ip_code) REFERENCES bird_ip_code(code);
ALTER TABLE bird ADD CONSTRAINT bird_code_city_fk FOREIGN KEY(code_city) REFERENCES bird_city_code(code);
CREATE TABLE bird_snowflake_state (
  allocator_id INT PRIMARY KEY,
  last_millis BIGINT NOT NULL,
  sequence_no INT NOT NULL
);
-- One persisted allocator for the whole database; all app replicas share node 0.
INSERT INTO bird_snowflake_state(allocator_id,last_millis,sequence_no) VALUES(1,0,0);
