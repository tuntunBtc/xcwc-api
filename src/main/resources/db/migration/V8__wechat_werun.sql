-- 微信登录与会话：每个微信登录令牌关联自己的服务端 session_key。
ALTER TABLE app_user ADD COLUMN wx_openid VARCHAR(64) NULL;
CREATE UNIQUE INDEX app_user_wx_openid_idx ON app_user(wx_openid);

CREATE TABLE wx_session (
  token_hash CHAR(64) PRIMARY KEY,
  session_key VARCHAR(64) NOT NULL,
  FOREIGN KEY (token_hash) REFERENCES auth_session(token_hash) ON DELETE CASCADE
);
