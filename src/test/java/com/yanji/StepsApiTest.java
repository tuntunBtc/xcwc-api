package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 微信步数兑换的端到端契约测试（调试模式：固定 code2Session 桩）。需 JDK21 + Maven 运行 `mvn test`。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StepsApiTest {
    @Autowired MockMvc mvc;
    @Autowired WxRunProperties props;
    @Autowired ObjectMapper mapper;
    @Autowired WxSessionStore sessions;
    @Autowired RegistrationService registration;
    @Autowired JdbcTemplate jdbc;

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String IV = "MDEyMzQ1Njc4OWFiY2RlZg=="; // 16 字节 base64，与调试 session_key 同串

    String token() throws Exception {
        return loginResult().get("token").asText();
    }

    JsonNode loginResult() throws Exception {
        String body = mvc.perform(post("/bird/v1/auth/wx-login").contentType("application/json").content("{\"code\":\"DEBUG_CODE\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    @Test void loginExposesPersistentIdentityAndActualSessionExpiry() throws Exception {
        Instant before = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        JsonNode first = loginResult();
        JsonNode second = loginResult();
        String user = first.path("userId").asText();
        String firstToken = first.path("token").asText();
        String secondToken = second.path("token").asText();
        assertThat(user).matches("[0-9a-f]{32}");
        assertThat(second.path("userId").asText()).isEqualTo(user);
        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(first.size()).isEqualTo(3); // Only token, userId and expiresAt; no WeChat secrets.
        assertThat(registration.userId("Bearer " + firstToken)).isEqualTo(user);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE wx_openid=?", Integer.class,
            props.debugOpenid.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(1);

        Instant expiresAt = Instant.parse(first.path("expiresAt").asText());
        assertThat(expiresAt).isBetween(before.plus(7, java.time.temporal.ChronoUnit.DAYS),
            Instant.now().plus(7, java.time.temporal.ChronoUnit.DAYS));
        assertThat(jdbc.queryForObject("SELECT expires_at FROM auth_session WHERE token_hash=?",
            java.sql.Timestamp.class, RegistrationService.digest(firstToken)).toInstant()).isEqualTo(expiresAt);
        mvc.perform(get("/bird/v1/auth/session").header("Authorization", "Bearer " + firstToken))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.userId").value(user))
            .andExpect(jsonPath("$.expiresAt").value(first.path("expiresAt").asText()))
            .andExpect(jsonPath("$.token").doesNotExist())
            .andExpect(jsonPath("$.openid").doesNotExist())
            .andExpect(jsonPath("$.sessionKey").doesNotExist());

        String otherUser = registration.ensureUserByOpenid("identity-fixture-" + UUID.randomUUID());
        String otherToken = registration.issueSessionForUser(otherUser).token();
        mvc.perform(get("/bird/v1/auth/session").param("userId", user).header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(otherUser));
        mvc.perform(get("/bird/v1/auth/session").param("userId", user))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.userId").doesNotExist());

        jdbc.update("UPDATE auth_session SET expires_at=? WHERE token_hash=?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(60)), RegistrationService.digest(firstToken));
        mvc.perform(get("/bird/v1/auth/session").header("Authorization", "Bearer " + firstToken))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.userId").doesNotExist());
        JsonNode renewed = loginResult();
        assertThat(renewed.path("userId").asText()).isEqualTo(user);
        assertThat(renewed.path("token").asText()).isNotEqualTo(firstToken).isNotEqualTo(secondToken);
        mvc.perform(post("/bird/v1/auth/logout").header("Authorization", "Bearer " + renewed.path("token").asText()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/bird/v1/auth/session").header("Authorization", "Bearer " + renewed.path("token").asText()))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/bird/v1/auth/session").header("Authorization", "Bearer " + secondToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(user))
            .andExpect(jsonPath("$.expiresAt").value(second.path("expiresAt").asText()));
    }

    String werunCipher(long stepTs, int steps, String appId) throws Exception {
        String json = "{\"stepInfoList\":[{\"timestamp\":" + stepTs + ",\"step\":" + steps + "}],"
            + "\"watermark\":{\"appid\":\"" + appId + "\",\"timestamp\":" + Instant.now().getEpochSecond() + "}}";
        return WxRunCrypto.encrypt(json, IV, props.debugSessionKey);
    }

    @Test void redeemFlowAndSameDayIdempotency() throws Exception {
        String token = token();
        long todayTs = LocalDate.now(SHANGHAI).atStartOfDay(SHANGHAI).toEpochSecond();
        String cipher = werunCipher(todayTs, 8600, props.appId);
        String low = werunCipher(todayTs, 999, props.appId);
        String req = "abc123ABC-xyz_0123456789";

        mvc.perform(post("/bird/v1/steps/redeem").header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"encryptedData\":\"" + low + "\",\"iv\":\"" + IV + "\",\"requestId\":\"" + req + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.granted").value(0))
            .andExpect(jsonPath("$.claimed").value(false));

        String r1 = mvc.perform(post("/bird/v1/steps/redeem").header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"encryptedData\":\"" + cipher + "\",\"iv\":\"" + IV + "\",\"requestId\":\"" + req + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode j1 = mapper.readTree(r1);
        assertThat(j1.get("granted").asInt()).isEqualTo(5);
        assertThat(j1.get("steps").asInt()).isEqualTo(8600);
        assertThat(j1.get("claimed").asBoolean()).isTrue();

        // 同 request_id 重复提交：replayed，不重复发分
        String r2 = mvc.perform(post("/bird/v1/steps/redeem").header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"encryptedData\":\"" + cipher + "\",\"iv\":\"" + IV + "\",\"requestId\":\"" + req + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode j2 = mapper.readTree(r2);
        assertThat(j2.get("granted").asInt()).isEqualTo(5);
        assertThat(j2.get("claimed").asBoolean()).isTrue();

        // 不同 request_id 同日再兑：同日守卫，仍 claimed，余额不变
        String r3 = mvc.perform(post("/bird/v1/steps/redeem").header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"encryptedData\":\"" + low + "\",\"iv\":\"" + IV + "\",\"requestId\":\"other_req_0123456789ABCDE\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode j3 = mapper.readTree(r3);
        assertThat(j3.get("granted").asInt()).isEqualTo(5);
        assertThat(j3.get("claimed").asBoolean()).isTrue();
    }

    @Test void wechatOnlyAccountAndPerTokenSession() throws Exception {
        mvc.perform(post("/bird/v1/auth/wx-login").contentType("application/json").content("{\"code\":\" \"}"))
            .andExpect(status().isBadRequest());
        String first = token();
        String second = token();
        assertThat(second).isNotEqualTo(first);
        String user = registration.userId("Bearer " + first);
        assertThat(registration.userId("Bearer " + second)).isEqualTo(user);
        String username = jdbc.queryForObject("SELECT username FROM app_user WHERE id=?", String.class, user);
        mvc.perform(post("/bird/v1/auth/login").contentType("application/json")
                .content(mapper.writeValueAsString(java.util.Map.of("username", username, "password", "not-a-user-password"))))
            .andExpect(status().isUnauthorized());
        assertThat(sessions.sessionKey(first)).isNotBlank();
        assertThat(sessions.sessionKey(second)).isNotBlank();
        registration.logout("Bearer " + first);
        assertThat(sessions.sessionKey(first)).isNull();
        assertThat(sessions.sessionKey(second)).isNotBlank();
    }

    @Test void wechatOpenIdMatchingUsesExactBytes() {
        String prefix = "openid-fixture-" + UUID.randomUUID();
        String first = registration.ensureUserByOpenid(prefix + "A");
        String second = registration.ensureUserByOpenid(prefix + "a");

        assertThat(second).isNotEqualTo(first);
        assertThat(registration.ensureUserByOpenid(prefix + "A")).isEqualTo(first);
        assertThat(registration.ensureUserByOpenid(prefix + "a")).isEqualTo(second);
    }

    @Test void watermarkMismatchRejected() throws Exception {
        String token = token();
        long todayTs = LocalDate.now(SHANGHAI).atStartOfDay(SHANGHAI).toEpochSecond();
        String cipher = werunCipher(todayTs, 8600, "wrong-appid");
        mvc.perform(post("/bird/v1/steps/redeem").header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"encryptedData\":\"" + cipher + "\",\"iv\":\"" + IV + "\",\"requestId\":\"wm_0123456789ABCDE\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void unauthorizedWithoutToken() throws Exception {
        mvc.perform(post("/bird/v1/steps/redeem").contentType("application/json")
                .content("{\"encryptedData\":\"x\",\"iv\":\"" + IV + "\",\"requestId\":\"z0123456789ABCDE\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void incrementalRedemptionCountsLegacyGrantsAndAllowsFurtherTiers() throws Exception {
        String user = registration.ensureUserByOpenid("step-progress-" + UUID.randomUUID());
        String auth = newProgressSession(user);
        LocalDate date = LocalDate.now(SHANGHAI);
        String request = "v2_grant_increment_0001";
        mvc.perform(post("/bird/v1/steps/redeem").header("Authorization", auth)
                .contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of(
                    "encryptedData", werunCipher(date.atStartOfDay(SHANGHAI).toEpochSecond(), 1600, props.appId),
                    "iv", IV, "requestId", "legacy_grant_progress_01"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.granted").value(1));
        jdbc.update("UPDATE app_user SET cloud_pet_enabled=TRUE WHERE id=?", user);

        var preview = progress("preview-v2", auth, date, 2750, null);
        assertThat(preview.path("stepPointsAvailable").asInt()).isEqualTo(1);
        assertThat(preview.path("stepPointsRedeemed").asInt()).isEqualTo(1);
        assertThat(preview.path("granted").asInt()).isZero();
        var first = progress("redeem-v2", auth, date, 2750, request);
        assertThat(first.path("granted").asInt()).isEqualTo(1);
        assertThat(first.path("stepPointsRedeemed").asInt()).isEqualTo(2);
        assertThat(first.path("stepPointsAvailable").asInt()).isZero();
        var stale = progress("preview-v2", auth, date, 1300, null);
        assertThat(stale.path("stepPointsAvailable").asInt()).isZero();
        assertThat(stale.path("stepPointsRedeemed").asInt()).isEqualTo(2);

        var repeated = progress("redeem-v2", newProgressSession(user), date, 9300, request);
        assertThat(repeated.path("replayed").asBoolean()).isTrue();
        assertThat(repeated.path("granted").asInt()).isEqualTo(1);
        assertThat(repeated.path("stepPointsAvailable").asInt()).isEqualTo(7);
        var extra = progress("redeem-v2", auth, date, 12800, "v2_grant_increment_0002");
        assertThat(extra.path("granted").asInt()).isEqualTo(10);
        assertThat(extra.path("stepPointsRedeemed").asInt()).isEqualTo(12);
        assertThat(progress("redeem-v2", auth, date, 12800, "v2_grant_increment_0003").path("granted").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT points_balance FROM app_user WHERE id=?", Long.class, user)).isEqualTo(12L);
        mvc.perform(post("/bird/v1/steps/redeem-v2").header("Authorization", auth).contentType("application/json")
                .content(mapper.writeValueAsString(java.util.Map.of(
                    "encryptedData", werunCipher(date.atStartOfDay(SHANGHAI).toEpochSecond(), 13000, props.appId),
                    "iv", IV, "requestId", "v2_grant_increment_0004", "expectedDate", date.minusDays(1).toString()))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STEPS_DAY_CHANGED"));
    }

    @Test void concurrentDeviceClaimsDoNotSpendTheSameTiers() throws Exception {
        String user = registration.ensureUserByOpenid("step-concurrent-" + UUID.randomUUID());
        String auth = newProgressSession(user);
        String otherAuth = newProgressSession(user);
        LocalDate date = LocalDate.now(SHANGHAI);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var one = executor.submit(() -> {
                start.await();
                return progress("redeem-v2", auth, date, 4800, "v2_parallel_request_0001").path("granted").asInt();
            });
            var two = executor.submit(() -> {
                start.await();
                return progress("redeem-v2", otherAuth, date, 4800, "v2_parallel_request_0002").path("granted").asInt();
            });
            start.countDown();
            assertThat(java.util.List.of(one.get(), two.get())).containsExactlyInAnyOrder(0, 4);
        }
        assertThat(jdbc.queryForObject("SELECT points_balance FROM app_user WHERE id=?", Long.class, user)).isEqualTo(4L);
    }

    private String newProgressSession(String user) {
        String token = registration.issueSessionForUser(user).token();
        sessions.save(token, props.debugSessionKey);
        return "Bearer " + token;
    }

    private JsonNode progress(String route, String auth, LocalDate day, int steps, String requestId) throws Exception {
        var body = new java.util.HashMap<String, String>();
        body.put("encryptedData", werunCipher(day.atStartOfDay(SHANGHAI).toEpochSecond(), steps, props.appId));
        body.put("iv", IV);
        if (requestId != null) {
            body.put("requestId", requestId);
            body.put("expectedDate", day.toString());
        }
        return mapper.readTree(mvc.perform(post("/bird/v1/steps/" + route).header("Authorization", auth)
                .contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test void suppliesAreDisabledByDefault() throws Exception {
        String body = """
            {"encryptedData":"x","iv":"x","installationId":"00000000000000000000000000000001",
             "birdCode":"HZ-HY-9000000000000000001","expectedDate":"2026-11-10"}
            """;
        for (String operation : java.util.List.of("preview", "claim")) {
            mvc.perform(post("/bird/v1/supplies/" + operation).contentType("application/json").content(body))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SUPPLY_DISABLED"));
        }
    }
}
