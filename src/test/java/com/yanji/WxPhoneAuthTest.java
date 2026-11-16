package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WxPhoneAuthTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registration;
    @Autowired WxPhoneAuthService service;
    @Autowired WxSessionStore sessions;
    @Autowired WxRunProperties props;
    @Autowired CloudPetService pets;
    @Autowired PointsService points;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean WxCode2SessionClient identity;
    @MockitoBean WxPhoneNumberClient phone;
    static final AtomicInteger IP = new AtomicInteger();
    String openid;
    static final String NUMBER = "19900000000"; // Synthetic fixture, never a customer number.
    static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZg==";

    @BeforeEach void setup() {
        openid = "phone-fixture-" + UUID.randomUUID();
        props.phoneLoginEnabled = true;
        when(identity.call("login-code")).thenReturn(new WxCode2SessionClient.WechatSession(openid, KEY));
        when(phone.call("phone-code")).thenReturn(new WxPhoneNumberClient.Phone("86", NUMBER));
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String body) {
        return post("/api/auth/wx-phone-login").header("X-Real-IP", "192.0.2." + IP.incrementAndGet())
            .contentType("application/json").content(body);
    }
    String body() { return "{\"code\":\"login-code\",\"phoneCode\":\"phone-code\"}"; }

    @Test void reusesExistingWechatUserAndReturnsOnlyMaskedPhone() throws Exception {
        String id = registration.ensureUserByOpenid(openid);
        var before = registration.issueSessionForUser(id);
        String auth = "Bearer " + before.token();
        var adopted = mapper.valueToTree(pets.adopt(auth, new CloudPetService.Adoption(UUID.randomUUID().toString(), registration.provision().id(), "小啾")));
        String petId = adopted.path("pet").path("petId").asText();
        pets.act(auth, petId, new CloudPetService.Command(UUID.randomUUID().toString(), adopted.path("pet").path("version").asText(), "FEED", null, null));
        var wallet = points.wallet(auth);
        JsonNode result = mapper.readTree(mvc.perform(login(body())).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString());
        assertThat(result.path("userId").asText()).isEqualTo(id);
        assertThat(result.path("phoneMasked").asText()).isEqualTo("+86 **** 0000");
        assertThat(result.toString()).doesNotContain(NUMBER, openid, KEY, "phone_e164");
        assertThat(registration.userId("Bearer " + result.path("token").asText())).isEqualTo(id);
        assertThat(registration.userId("Bearer " + before.token())).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT phone_e164 FROM user_verified_phone WHERE user_id=?", String.class, id)).isEqualTo("+86" + NUMBER);
        mvc.perform(get("/api/me/pets").header("Authorization", "Bearer " + result.path("token").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(id));
        mvc.perform(get("/api/me/pets/" + petId).header("Authorization", "Bearer " + result.path("token").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pet.nickname").value("小啾"))
            .andExpect(jsonPath("$.pet.inventory.food").value(2)).andExpect(jsonPath("$.pet.care.satiety").value(90));
        assertThat(points.wallet("Bearer " + result.path("token").asText())).isEqualTo(wallet);
    }

    @Test void ignoresUntrustedPhoneFieldsAndDoesNotMergeDifferentWechatAccounts() throws Exception {
        var first = service.login("login-code", "phone-code");
        when(identity.call("other-code")).thenReturn(new WxCode2SessionClient.WechatSession("other-" + UUID.randomUUID(), KEY));
        var second = service.login("other-code", "phone-code");
        assertThat(second.userId()).isNotEqualTo(first.userId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_verified_phone WHERE user_id IN (?,?)", Integer.class, first.userId(), second.userId())).isEqualTo(2);
        mvc.perform(login("{\"code\":\"login-code\",\"phoneCode\":\"phone-code\",\"phoneNumber\":\"18811112222\",\"userId\":\"attacker\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(first.userId()));
        assertThat(jdbc.queryForObject("SELECT phone_e164 FROM user_verified_phone WHERE user_id=?", String.class, first.userId())).isEqualTo("+86" + NUMBER);
    }

    @Test void failedOrReusedAuthorizationCreatesNoUserOrSession() throws Exception {
        when(phone.call("phone-code")).thenThrow(new RegistrationService.Failure(HttpStatus.UNAUTHORIZED, "WX_PHONE_CODE_INVALID", "请重新授权"));
        mvc.perform(login(body())).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WX_PHONE_CODE_INVALID"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE wx_openid=?", Integer.class, openid.getBytes(StandardCharsets.UTF_8))).isZero();
    }

    @Test void invalidIdentityNeverSpendsPhoneLookup() throws Exception {
        when(identity.call("login-code")).thenThrow(new RegistrationService.Failure(HttpStatus.UNAUTHORIZED, "WX_LOGIN_FAILED", "重新登录"));
        mvc.perform(login(body())).andExpect(status().isUnauthorized());
        verifyNoInteractions(phone);
    }

    @Test void persistenceFailureRollsBackIdentityPhoneAndSessionTogether() {
        WxSessionStore broken = mock(WxSessionStore.class);
        doThrow(new IllegalStateException("synthetic storage failure")).when(broken).save(anyString(), anyString());
        var failing = new WxPhoneAuthService(registration, identity, phone, broken, props, jdbc, transactions);
        assertThatThrownBy(() -> failing.login("login-code", "phone-code")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE wx_openid=?", Integer.class, openid.getBytes(StandardCharsets.UTF_8))).isZero();

        var existing = service.login("login-code", "phone-code");
        int sessionCount = jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id=?", Integer.class, existing.userId());
        when(phone.call("phone-code")).thenReturn(new WxPhoneNumberClient.Phone("86", "19900000001"));
        assertThatThrownBy(() -> failing.login("login-code", "phone-code")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT phone_e164 FROM user_verified_phone WHERE user_id=?", String.class, existing.userId())).isEqualTo("+86" + NUMBER);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id=?", Integer.class, existing.userId())).isEqualTo(sessionCount);
        assertThat(registration.userId("Bearer " + existing.token())).isEqualTo(existing.userId());
    }

    @Test void rejectsMissingCodesAndSupportsDisabledRollout() throws Exception {
        for (String invalid : new String[]{"{}", "{\"code\":\"login-code\",\"phoneCode\":\" \"}", "{\"code\":\"login-code\",\"phoneCode\":\"" + "x".repeat(513) + "\"}"}) {
            mvc.perform(login(invalid)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(phone, identity);
        props.phoneLoginEnabled = false;
        try { mvc.perform(login(body())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WX_PHONE_UNCONFIGURED")); }
        finally { props.phoneLoginEnabled = true; }
    }

    @Test void repeatedAndConcurrentAuthorizationKeepsSinglePhoneRecord() throws Exception {
        // Exercise the new-user race and the existing-user row lock in the real database.
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var jobs = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> { start.await(); return service.login("login-code", "phone-code"); })).toList();
            start.countDown();
            var values = new java.util.ArrayList<WxPhoneAuthService.Result>();
            for (var job : jobs) values.add(job.get(20, TimeUnit.SECONDS));
            assertThat(values.stream().map(WxPhoneAuthService.Result::userId).distinct().count()).isEqualTo(1);
            assertThat(values.stream().map(WxPhoneAuthService.Result::token).distinct().count()).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_verified_phone WHERE user_id=?", Integer.class, values.getFirst().userId())).isEqualTo(1);
        }
    }
}
