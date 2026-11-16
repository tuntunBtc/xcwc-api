-- RuoYi 3.9.2 compatible management tables. No demo users/passwords or destructive SQL.

create table sys_dept (
  dept_id           bigint      not null auto_increment,
  parent_id         bigint      default 0,
  ancestors         varchar(50)     default '',
  dept_name         varchar(30)     default '',
  order_num         int          default 0,
  leader            varchar(20)     default null,
  phone             varchar(11)     default null,
  email             varchar(50)     default null,
  status            char(1)         default '0',
  del_flag          char(1)         default '0',
  create_by         varchar(64)     default '',
  create_time 	    datetime,
  update_by         varchar(64)     default '',
  update_time       datetime,
  primary key (dept_id)
);

create table sys_user (
  user_id           bigint      not null auto_increment,
  dept_id           bigint      default null,
  user_name         varchar(30)     not null,
  nick_name         varchar(30)     not null,
  user_type         varchar(2)      default '00',
  email             varchar(50)     default '',
  phonenumber       varchar(11)     default '',
  sex               char(1)         default '0',
  avatar            varchar(100)    default '',
  password          varchar(100)    default '',
  status            char(1)         default '0',
  del_flag          char(1)         default '0',
  login_ip          varchar(128)    default '',
  login_date        datetime,
  pwd_update_date   datetime,
  create_by         varchar(64)     default '',
  create_time       datetime,
  update_by         varchar(64)     default '',
  update_time       datetime,
  remark            varchar(500)    default null,
  primary key (user_id)
);

create table sys_role (
  role_id              bigint      not null auto_increment,
  role_name            varchar(30)     not null,
  role_key             varchar(100)    not null,
  role_sort            int          not null,
  data_scope           char(1)         default '1',
  menu_check_strictly  tinyint      default 1,
  dept_check_strictly  tinyint      default 1,
  status               char(1)         not null,
  del_flag             char(1)         default '0',
  create_by            varchar(64)     default '',
  create_time          datetime,
  update_by            varchar(64)     default '',
  update_time          datetime,
  remark               varchar(500)    default null,
  primary key (role_id)
);

create table sys_menu (
  menu_id           bigint      not null auto_increment,
  menu_name         varchar(50)     not null,
  parent_id         bigint      default 0,
  order_num         int          default 0,
  path              varchar(200)    default '',
  component         varchar(255)    default null,
  query             varchar(255)    default null,
  route_name        varchar(50)     default '',
  is_frame          int          default 1,
  is_cache          int          default 0,
  menu_type         char(1)         default '',
  visible           char(1)         default 0,
  status            char(1)         default 0,
  perms             varchar(100)    default null,
  icon              varchar(100)    default '#',
  create_by         varchar(64)     default '',
  create_time       datetime,
  update_by         varchar(64)     default '',
  update_time       datetime,
  remark            varchar(500)    default '',
  primary key (menu_id)
);

create table sys_user_role (
  user_id   bigint not null,
  role_id   bigint not null,
  primary key(user_id, role_id)
);

create table sys_role_menu (
  role_id   bigint not null,
  menu_id   bigint not null,
  primary key(role_id, menu_id)
);

create table sys_oper_log (
  oper_id           bigint      not null auto_increment,
  title             varchar(50)     default '',
  business_type     int          default 0,
  method            varchar(200)    default '',
  request_method    varchar(10)     default '',
  operator_type     int          default 0,
  oper_name         varchar(50)     default '',
  dept_name         varchar(50)     default '',
  oper_url          varchar(255)    default '',
  oper_ip           varchar(128)    default '',
  oper_location     varchar(255)    default '',
  oper_param        varchar(2000)   default '',
  json_result       varchar(2000)   default '',
  status            int          default 0,
  error_msg         varchar(2000)   default '',
  oper_time         datetime,
  cost_time         bigint      default 0,
  primary key (oper_id),
  key idx_sys_oper_log_bt (business_type),
  key idx_sys_oper_log_s  (status),
  key idx_sys_oper_log_ot (oper_time)
);


CREATE UNIQUE INDEX uq_admin_user_name ON sys_user(user_name);
CREATE TABLE admin_session (token_hash CHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, expires_at TIMESTAMP NOT NULL, FOREIGN KEY(user_id) REFERENCES sys_user(user_id));
CREATE TABLE admin_login_attempt (name_hash CHAR(64) PRIMARY KEY, failures INT NOT NULL, locked_until TIMESTAMP NULL);
CREATE TABLE content_workspace (id INT PRIMARY KEY, version BIGINT NOT NULL, published_id VARCHAR(32), draft LONGTEXT NOT NULL);
INSERT INTO content_workspace(id,version,draft) VALUES(1,0,'{"schemaVersion":1,"characters":[],"appearances":[],"accessories":[],"pages":[],"products":[]}');
CREATE TABLE content_release (id VARCHAR(32) PRIMARY KEY, workspace_version BIGINT NOT NULL, payload LONGTEXT NOT NULL, actor_id BIGINT NOT NULL, created_at TIMESTAMP NOT NULL);
CREATE TABLE content_asset (id VARCHAR(32) PRIMARY KEY, sha256 CHAR(64) NOT NULL, mime VARCHAR(50) NOT NULL, size_bytes BIGINT NOT NULL, width INT NOT NULL, height INT NOT NULL, original_name VARCHAR(160) NOT NULL, license_note VARCHAR(500) NOT NULL, created_by BIGINT NOT NULL, created_at TIMESTAMP NOT NULL);
CREATE TABLE content_release_asset (release_id VARCHAR(32) NOT NULL, asset_id VARCHAR(32) NOT NULL, PRIMARY KEY(release_id,asset_id), FOREIGN KEY(release_id) REFERENCES content_release(id), FOREIGN KEY(asset_id) REFERENCES content_asset(id));
CREATE TABLE content_command (actor_id BIGINT NOT NULL, request_id VARBINARY(64) NOT NULL, payload_hash CHAR(64) NOT NULL, result_json LONGTEXT NOT NULL, PRIMARY KEY(actor_id,request_id));
CREATE TABLE content_entity (bird_id VARCHAR(32) PRIMARY KEY, character_id VARCHAR(64) NOT NULL, FOREIGN KEY(bird_id) REFERENCES bird(id));
CREATE TABLE content_preview (token_hash CHAR(64) PRIMARY KEY, actor_id BIGINT NOT NULL, payload LONGTEXT NOT NULL, expires_at TIMESTAMP NOT NULL);
CREATE TABLE wardrobe_entitlement (user_id VARCHAR(32) NOT NULL, item_id VARCHAR(64) NOT NULL, item_type VARCHAR(16) NOT NULL, item_json LONGTEXT NOT NULL, release_id VARCHAR(32) NOT NULL, acquired_at TIMESTAMP NOT NULL, PRIMARY KEY(user_id,item_id), FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE);
CREATE TABLE wardrobe_outfit (binding_id VARCHAR(32) PRIMARY KEY, version BIGINT NOT NULL, appearance_id VARCHAR(64) NOT NULL, accessories_json LONGTEXT NOT NULL, FOREIGN KEY(binding_id) REFERENCES bird_binding(id) ON DELETE CASCADE);
CREATE TABLE wardrobe_operation (user_id VARCHAR(32) NOT NULL, request_id VARBINARY(64) NOT NULL, payload_hash CHAR(64) NOT NULL, result_json LONGTEXT NOT NULL, PRIMARY KEY(user_id,request_id), FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE);
INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status) VALUES(1,'系统管理','admin',1,'0'),(2,'内容编辑','editor',2,'0'),(3,'内容发布','publisher',3,'0'),(4,'只读查看','reader',4,'0');


INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status,perms) VALUES(1,'content:read',0,1,'','F','0','0','content:read');

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1,1);

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(2,1);

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(3,1);

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(4,1);

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status,perms) VALUES(2,'content:edit',0,2,'','F','0','0','content:edit');

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1,2);

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(2,2);

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status,perms) VALUES(3,'content:publish',0,3,'','F','0','0','content:publish');

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1,3);

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(3,3);

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status,perms) VALUES(4,'content:preview',0,4,'','F','0','0','content:preview');

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1,4);

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(2,4);

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(3,4);

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status,perms) VALUES(5,'system:manage',0,5,'','F','0','0','system:manage');

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1,5);

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status,perms) VALUES(6,'content:audit',0,6,'','F','0','0','content:audit');

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1,6);

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(3,6);

INSERT INTO sys_role_menu(role_id,menu_id) VALUES(4,6);
