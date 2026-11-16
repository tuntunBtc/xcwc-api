package com.yanji;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 微信登录编排：code2Session → 绑定/创建账号 → 下发 Bearer 令牌 → 缓存 session_key（仅服务端）。 */
@Service
public class WxAuthService {
    private final RegistrationService registration;
    private final WxCode2SessionClient client;
    private final WxSessionStore sessions;

    public WxAuthService(RegistrationService registration, WxCode2SessionClient client, WxSessionStore sessions) {
        this.registration = registration;
        this.client = client;
        this.sessions = sessions;
    }

    public record LoginResult(String token, String userId, Instant expiresAt) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LoginResult login(String code) {
        WxCode2SessionClient.WechatSession wx = client.call(code);
        String userId = registration.ensureUserByOpenid(wx.openid());
        RegistrationService.Session session = registration.issueSessionForUser(userId);
        sessions.save(session.token(), wx.sessionKey());
        return new LoginResult(session.token(), userId, session.expiresAt());
    }
}
