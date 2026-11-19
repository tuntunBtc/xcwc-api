CREATE TABLE brand_ip_revision (
  character_id varchar(32) NOT NULL PRIMARY KEY,
  draft_revision bigint NOT NULL DEFAULT 0,
  state_revision bigint NOT NULL DEFAULT 0,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE brand_ip_history (
  id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
  character_id varchar(32) NOT NULL,
  release_id varchar(32) NOT NULL,
  action varchar(20) NOT NULL,
  actor_id bigint NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_brand_ip_history ON brand_ip_history(character_id,id);
INSERT INTO sys_menu(menu_name,menu_type,perms,status) VALUES
('品牌IP查看','F','brand:read','0'),('品牌IP编辑','F','brand:edit','0'),
('品牌IP预览','F','brand:preview','0'),('品牌IP发布','F','brand:publish','0'),
('品牌IP上下架','F','brand:status','0'),('品牌IP回滚','F','brand:rollback','0'),
('品牌IP日志','F','brand:audit','0');
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r CROSS JOIN sys_menu m
WHERE m.perms LIKE 'brand:%' AND (r.role_id=1
OR (r.role_id=2 AND m.perms IN ('brand:read','brand:edit','brand:preview'))
OR (r.role_id=3 AND m.perms IN ('brand:read','brand:preview','brand:publish','brand:status','brand:rollback','brand:audit'))
OR (r.role_id=4 AND m.perms IN ('brand:read','brand:audit')));
