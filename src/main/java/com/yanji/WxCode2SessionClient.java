package com.yanji;

/** code2Session 抽象：把微信登录 code 换成 openid + session_key。生产走微信接口，调试用固定桩。 */
public interface WxCode2SessionClient {
    WechatSession call(String code);

    record WechatSession(String openid, String sessionKey) {}
}
