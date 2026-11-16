package com.yanji;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"app.wx.supply-enabled=true", "app.wx.supply-steps=3000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplyApiTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String FIXTURE_KEY = "MDEyMzQ1Njc4OWFiY2RlZg==";
    private static final String FIRST_INSTALLATION = "00000000000000000000000000000001";
    private static final String SECOND_INSTALLATION = "00000000000000000000000000000002";
    private static final AtomicLong ENTITY = new AtomicLong(9_100_000_000_000_000_000L);
    private static final AtomicInteger REMOTE = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registration;
    @Autowired WxRunProperties props;
    @MockitoBean Clock clock;
    @MockitoBean WxCode2SessionClient wechat;

    private Instant now;
    private String remote;
    private String auth;
    private String bird;

    @BeforeEach void setup() throws Exception {
        now = Instant.parse("2026-11-10T15:59:58Z");
        when(clock.instant()).thenAnswer(call -> now);
        when(wechat.call(anyString())).thenAnswer(call ->
            new WxCode2SessionClient.WechatSession("supply-fixture-" + call.getArgument(0), FIXTURE_KEY));
        remote = "198.51.100." + REMOTE.incrementAndGet();
        auth = login();
        bird = createBird(true);
    }

    @Test void previewAndUnderThresholdNeverGrantThenClaimIsIdempotent() throws Exception {
        String date = date();
        String low = encryptedSteps(2999, props.appId);
        String met = encryptedSteps(3000, props.appId);
        supply(auth, "preview", low, FIRST_INSTALLATION, bird, null).andExpect(status().isOk())
            .andExpect(jsonPath("$.steps").value(2999)).andExpect(jsonPath("$.requiredSteps").value(3000))
            .andExpect(jsonPath("$.date").value(date)).andExpect(jsonPath("$.claimable").value(false));
        supply(auth, "claim", low, FIRST_INSTALLATION, bird, date).andExpect(status().isOk())
            .andExpect(jsonPath("$.claimed").value(false)).andExpect(jsonPath("$.receipt").isEmpty());
        supply(auth, "preview", met, FIRST_INSTALLATION, bird, null).andExpect(status().isOk())
            .andExpect(jsonPath("$.claimable").value(true)).andExpect(jsonPath("$.receipt").isEmpty());
        assertThat(receiptCount(auth)).isZero();

        var granted = body(supply(auth, "claim", met, FIRST_INSTALLATION, bird, date));
        assertThat(granted.path("claimed").asBoolean()).isTrue();
        assertThat(granted.path("claimable").asBoolean()).isFalse();
        JsonNode receipt = granted.path("receipt");
        assertThat(receipt.path("id").asText()).matches("[0-9a-f]{32}");
        assertThat(receipt.path("date").asText()).isEqualTo(date);
        assertThat(receipt.path("birdCode").asText()).isEqualTo(bird);
        assertThat(receipt.path("food").asInt()).isEqualTo(1);
        assertThat(receipt.path("water").asInt()).isEqualTo(1);
        assertThat(body(supply(auth, "claim", low, FIRST_INSTALLATION, bird, date)).path("receipt")).isEqualTo(receipt);
        assertThat(body(supply(auth, "preview", met, FIRST_INSTALLATION, bird, null)).path("receipt")).isEqualTo(receipt);
        assertThat(receiptCount(auth)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_ledger WHERE user_id=?", Integer.class,
            registration.userId(auth))).isZero();
    }

    @Test void receiptIsScopedToAccountInstallationAndBird() throws Exception {
        String met = encryptedSteps(3200, props.appId);
        var first = body(supply(auth, "claim", met, FIRST_INSTALLATION, bird, date())).path("receipt");
        String otherBird = createBird(true);
        var foreignInstallation = body(supply(auth, "claim", met, SECOND_INSTALLATION, bird, date()));
        var foreignBird = body(supply(auth, "preview", met, FIRST_INSTALLATION, otherBird, null));
        for (JsonNode foreign : java.util.List.of(foreignInstallation, foreignBird)) {
            assertThat(foreign.path("claimed").asBoolean()).isTrue();
            assertThat(foreign.path("claimable").asBoolean()).isFalse();
            assertThat(foreign.path("receipt").isNull()).isTrue();
        }
        String secondAccount = login();
        var second = body(supply(secondAccount, "claim", met, FIRST_INSTALLATION, bird, date())).path("receipt");
        assertThat(second.path("id").asText()).isNotBlank().isNotEqualTo(first.path("id").asText());
        assertThat(receiptCount(auth)).isEqualTo(1);
        assertThat(receiptCount(secondAccount)).isEqualTo(1);
    }

    @Test void rejectsStaleDateUntrustedWatermarkAndInactiveBird() throws Exception {
        String met = encryptedSteps(3100, props.appId);
        supply(null, "preview", met, FIRST_INSTALLATION, bird, null).andExpect(status().isUnauthorized());
        supply(auth, "preview", encryptedSteps(3100, "other-app"), FIRST_INSTALLATION, bird, null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WERUN_WATERMARK_INVALID"));
        supply(auth, "claim", met, FIRST_INSTALLATION, "HZ-HY-9000000000000000001", date())
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BIRD_NOT_FOUND"));
        supply(auth, "claim", met, FIRST_INSTALLATION, createBird(false), date())
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("BIRD_INACTIVE"));
        String yesterday = date();
        now = now.plusSeconds(3);
        String newDay = encryptedSteps(3200, props.appId);
        supply(auth, "claim", newDay, FIRST_INSTALLATION, bird, yesterday)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPLY_DAY_CHANGED"));
        assertThat(receiptCount(auth)).isZero();
        supply(auth, "claim", newDay, FIRST_INSTALLATION, bird, date())
            .andExpect(status().isOk()).andExpect(jsonPath("$.receipt.date").value(date()));
    }

    @Test void previewRejectsUnavailableBirdBeforeGrantButCanRecoverGrantedReceipt() throws Exception {
        String met = encryptedSteps(3100, props.appId);
        supply(auth, "preview", met, FIRST_INSTALLATION, "HZ-HY-9000000000000000001", null)
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BIRD_NOT_FOUND"));
        supply(auth, "preview", met, FIRST_INSTALLATION, createBird(false), null)
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("BIRD_INACTIVE"));
        assertThat(receiptCount(auth)).isZero();

        JsonNode receipt = body(supply(auth, "claim", met, FIRST_INSTALLATION, bird, date())).path("receipt");
        assertThat(receipt.isObject()).isTrue();
        jdbc.update("UPDATE bird SET active=? WHERE bird_code=?", false, bird);
        assertThat(body(supply(auth, "preview", met, FIRST_INSTALLATION, bird, null)).path("receipt")).isEqualTo(receipt);
        assertThat(body(supply(auth, "claim", met, FIRST_INSTALLATION, bird, date())).path("receipt")).isEqualTo(receipt);
        assertThat(receiptCount(auth)).isEqualTo(1);
    }

    @Test void simultaneousClaimsProduceOnlyOneReceipt() throws Exception {
        String met = encryptedSteps(3900, props.appId);
        String date = date();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return body(supply(auth, "claim", met, FIRST_INSTALLATION, bird, date));
            });
            var second = pool.submit(() -> {
                start.await();
                return body(supply(auth, "claim", met, SECOND_INSTALLATION, bird, date));
            });
            start.countDown();
            JsonNode a = first.get(15, TimeUnit.SECONDS);
            JsonNode b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.path("claimed").asBoolean()).isTrue();
            assertThat(b.path("claimed").asBoolean()).isTrue();
            assertThat(a.path("receipt").isNull()).isNotEqualTo(b.path("receipt").isNull());
        }
        assertThat(receiptCount(auth)).isEqualTo(1);
    }

    private String login() throws Exception {
        var result = body(mvc.perform(post("/api/auth/wx-login").contentType("application/json")
            .with(request -> { request.setRemoteAddr(remote); return request; })
            .content(mapper.writeValueAsBytes(Map.of("code", UUID.randomUUID().toString())))));
        return "Bearer " + result.path("token").asText();
    }

    private String createBird(boolean active) {
        String code = "HZ-HY-" + ENTITY.incrementAndGet();
        jdbc.update("UPDATE bird SET bird_code=?,active=? WHERE id=?", code, active, registration.provision().id());
        return code;
    }

    private String date() { return now.atZone(SHANGHAI).toLocalDate().toString(); }

    private String encryptedSteps(int steps, String appId) throws Exception {
        long dayStart = LocalDate.parse(date()).atStartOfDay(SHANGHAI).toEpochSecond();
        String data = mapper.writeValueAsString(Map.of("stepInfoList", java.util.List.of(Map.of("timestamp", dayStart, "step", steps)),
            "watermark", Map.of("appid", appId, "timestamp", now.getEpochSecond())));
        return WxRunCrypto.encrypt(data, FIXTURE_KEY, FIXTURE_KEY);
    }

    private ResultActions supply(String token, String operation, String encrypted, String installation, String code,
                                 String expectedDate) throws Exception {
        var payload = new LinkedHashMap<>(Map.of("encryptedData", encrypted, "iv", FIXTURE_KEY,
            "installationId", installation, "birdCode", code));
        if (expectedDate != null) payload.put("expectedDate", expectedDate);
        var request = post("/api/supplies/" + operation).contentType("application/json")
            .with(value -> { value.setRemoteAddr(remote); return value; }).content(mapper.writeValueAsBytes(payload));
        if (token != null) request.header("Authorization", token);
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private int receiptCount(String token) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM wx_supply_claim WHERE user_id=?", Integer.class, registration.userId(token));
    }
}
