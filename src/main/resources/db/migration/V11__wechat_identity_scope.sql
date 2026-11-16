ALTER TABLE app_user ADD COLUMN wx_appid VARBINARY(64) NULL;
DROP INDEX app_user_wx_openid_idx ON app_user;
CREATE UNIQUE INDEX app_user_wx_identity_idx ON app_user(wx_appid,wx_openid);
