-- Match opaque WeChat IDs by their bytes instead of the table collation.
ALTER TABLE app_user MODIFY COLUMN wx_openid VARBINARY(256) NULL;
