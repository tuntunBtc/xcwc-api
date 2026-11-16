package com.yanji;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WxPhoneAuthService {
    private final RegistrationService registration;
    private final WxCode2SessionClient identityClient;
    private final WxPhoneNumberClient phoneClient;
    private final WxSessionStore sessions;
    private final WxRunProperties props;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public WxPhoneAuthService(RegistrationService registration, WxCode2SessionClient identityClient,
            WxPhoneNumberClient phoneClient, WxSessionStore sessions, WxRunProperties props,
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.registration = registration; this.identityClient = identityClient; this.phoneClient = phoneClient;
        this.sessions = sessions; this.props = props; this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record Result(String token, String userId, Instant expiresAt, String phoneMasked) {}

    public Result login(String code, String phoneCode) {
        if (!props.phoneLoginEnabled) throw new RegistrationService.Failure(HttpStatus.SERVICE_UNAVAILABLE,
            "WX_PHONE_UNCONFIGURED", "手机号登录尚未开放，请使用微信登录");
        // Verify the identity before the chargeable phone lookup. Network calls never hold a DB transaction.
        var identity = identityClient.call(code);
        var phone = phoneClient.call(phoneCode);
        return transaction.execute(status -> {
            String userId = registration.ensureUserByOpenid(identity.openid());
            jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, userId);
            Timestamp now = Timestamp.from(Instant.now());
            if (jdbc.queryForList("SELECT user_id FROM user_verified_phone WHERE user_id=?", userId).isEmpty()) {
                jdbc.update("INSERT INTO user_verified_phone(user_id,phone_e164,verified_at) VALUES(?,?,?)", userId, phone.e164(), now);
            } else {
                jdbc.update("UPDATE user_verified_phone SET phone_e164=?,verified_at=? WHERE user_id=?", phone.e164(), now, userId);
            }
            var session = registration.issueSessionForUser(userId);
            sessions.save(session.token(), identity.sessionKey());
            return new Result(session.token(), userId, session.expiresAt(), phone.masked());
        });
    }
}
