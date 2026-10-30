package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationTest {
    @Autowired RegistrationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM points_ledger"); jdbc.update("DELETE FROM user_skin");
        jdbc.update("DELETE FROM bird"); jdbc.update("DELETE FROM auth_session"); jdbc.update("DELETE FROM app_user");
    }
    String auth(RegistrationService.Session session) { return "Bearer " + session.token(); }

    @Test void demoSeedPreservesOwnershipAndAttributes() throws Exception {
        service.seedDemo();
        var owner = service.register("demo_owner", "password-12345");
        String id = "00000000000000000000000000000001";
        var claimed = service.claim(auth(owner), id, "11111111111111111111111111111111", "杭州小白");
        assertThat(claimed.cityName()).isEqualTo("杭州");
        assertThat(claimed.skinId()).isEqualTo("demo_white");
        service.seedDemo();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bird", Integer.class)).isEqualTo(3);
        assertThat(service.mine(auth(owner)).getFirst().name()).isEqualTo("杭州小白");
        mvc.perform(get("/api/birds/" + id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.cityCode").value("hangzhou"))
            .andExpect(jsonPath("$.skinName").value("测试白色皮肤"));
    }

    @Test void registerAndLoginStoreOnlyHashes() {
        var session = service.register("owner", "password-12345");
        assertThat(service.userId(auth(session))).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user", String.class)).doesNotContain("password-12345");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM auth_session", String.class)).isNotEqualTo(session.token());
        assertThat(service.login("owner", "password-12345").token()).isNotEqualTo(session.token());
        assertThatThrownBy(() -> service.login("owner", "wrong-password")).isInstanceOf(RegistrationService.Failure.class);
        assertThatThrownBy(() -> service.register("owner", "password-67890")).isInstanceOf(RegistrationService.Failure.class);
    }

    @Test void expiredAndRevokedSessionsFail() {
        var session = service.register("owner", "password-12345");
        service.logout(auth(session));
        assertThatThrownBy(() -> service.userId(auth(session))).isInstanceOf(RegistrationService.Failure.class);
        var next = service.login("owner", "password-12345");
        jdbc.update("UPDATE auth_session SET expires_at=?", Timestamp.from(Instant.now().minusSeconds(60)));
        assertThatThrownBy(() -> service.userId(auth(next))).isInstanceOf(RegistrationService.Failure.class);
    }

    @Test void bindingIsPrivatePersistentAndRetrySafe() throws Exception {
        var owner = service.register("owner", "password-12345");
        var other = service.register("other", "password-12345");
        var bird = service.provision();
        assertThat(service.find(bird.id()).claimed()).isFalse();
        assertThatThrownBy(() -> service.claim(auth(owner), bird.id(), "wrong", "名字")).isInstanceOf(RegistrationService.Failure.class);
        assertThat(service.claim(auth(owner), bird.id(), bird.claimCode(), "我的燕叽").name()).isEqualTo("我的燕叽");
        service.claim(auth(owner), bird.id(), bird.claimCode(), "不能被重试改名");
        assertThat(service.mine(auth(owner))).hasSize(1);
        assertThat(service.mine(auth(owner)).getFirst().name()).isEqualTo("我的燕叽");
        assertThat(service.mine(auth(other))).isEmpty();
        assertThatThrownBy(() -> service.claim(auth(other), bird.id(), bird.claimCode(), "抢绑")).isInstanceOf(RegistrationService.Failure.class);
        assertThat(jdbc.queryForObject("SELECT claim_hash FROM bird", String.class)).isNotEqualTo(bird.claimCode());
        mvc.perform(get("/api/birds/" + bird.id())).andExpect(status().isOk()).andExpect(jsonPath("$.claimed").value(true))
            .andExpect(jsonPath("$.name").value("燕叽")).andExpect(jsonPath("$.claimHash").doesNotExist()).andExpect(jsonPath("$.ownerId").doesNotExist());
        mvc.perform(get("/api/me/birds")).andExpect(status().isUnauthorized());
        assertThat(service.mine(auth(service.login("owner", "password-12345")))).hasSize(1);
    }

    @Test void concurrentClaimHasOnlyOneWinner() throws Exception {
        var owner = service.register("owner", "password-12345");
        var other = service.register("other", "password-12345");
        var bird = service.provision();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.List.of(owner, other).stream().map(session -> executor.submit(() -> {
                start.await();
                try { service.claim(auth(session), bird.id(), bird.claimCode(), "燕叽"); return true; }
                catch (RegistrationService.Failure error) { assertThat(error.code).isEqualTo("ALREADY_CLAIMED"); return false; }
            })).toList();
            start.countDown();
            int winners = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test void apiPrefixAndUnknownRoutes() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/api/me/birds")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mvc.perform(get("/api/api/me/birds")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));
    }

    @Test void inputValidationAndErrors() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content("{\"username\":\"bad\",\"password\":\"123\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/birds/unknown")).andExpect(status().isNotFound());
        mvc.perform(post("/api/birds/unknown/claim").contentType("application/json").content("{\"code\":\"00000000000000000000000000000000\",\"name\":\"燕叽\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void authenticationIsRateLimited() throws Exception {
        for (int index = 0; index < 20; index++) {
            mvc.perform(post("/api/auth/login").with(request -> { request.setRemoteAddr("192.0.2.77"); return request; }).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/auth/login").with(request -> { request.setRemoteAddr("192.0.2.77"); return request; }).contentType("application/json").content("{}"))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
    }
}
