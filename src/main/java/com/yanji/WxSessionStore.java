package com.yanji;

/** 每个微信登录令牌对应的密钥，仅保存在服务端。 */
public interface WxSessionStore {
    String sessionKey(String token);
    void save(String token, String sessionKey);
}
