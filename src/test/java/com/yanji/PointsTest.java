package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PointsTest {
    @Autowired PointsService points;
    @Autowired RegistrationService registration;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    String auth, other, user, bird, bird2;
    Instant now;
    String key() { return UUID.randomUUID().toString(); }
    void time(Instant t) { now=t; when(clock.instant()).thenReturn(t); }
    void advance(long seconds) { time(now.plusSeconds(seconds)); }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM checkin_work"); jdbc.update("DELETE FROM checkin_reward_source"); jdbc.update("DELETE FROM checkin_reward_day"); jdbc.update("DELETE FROM bird_binding"); jdbc.update("DELETE FROM points_ledger"); jdbc.update("DELETE FROM user_skin");
        jdbc.update("DELETE FROM bird"); jdbc.update("DELETE FROM auth_session"); jdbc.update("DELETE FROM app_user");
        var session=registration.register("points_owner", "password-12345");
        auth="Bearer "+session.token(); user=registration.userId(auth);
        other="Bearer "+registration.register("points_other", "password-12345").token();
        jdbc.update("UPDATE app_user SET initial_reward_received=TRUE");
        var a=registration.provision(); var b=registration.provision();
        bird=a.id(); bird2=b.id();
        registration.claim(auth,bird,a.claimCode(),"第一只"); registration.claim(auth,bird2,b.claimCode(),"第二只");
        time(Instant.parse("2026-10-29T02:00:00Z"));
    }
    void fails(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RegistrationService.Failure.class,e -> assertThat(e.code).isEqualTo(code));
    }
    void earn(int count) {
        for(int i=0;i<count;i++) {
            if(i>0 && i%10==0) advance(86400);
            else advance(30);
            points.pet(auth,bird,key());
        }
    }
    @Test void rewardRetryCooldownAndSharedLimit() {
        String request=key();
        var first=points.pet(auth,bird,request);
        assertThat(first.entry().delta()).isEqualTo(1);
        assertThat(points.pet(auth,bird,request).replayed()).isTrue();
        fails("IDEMPOTENCY_CONFLICT",()->points.pet(auth,bird2,request));
        fails("REWARD_COOLDOWN",()->points.pet(auth,bird2,key()));
        advance(30);
        for(int i=0;i<9;i++) { points.pet(auth,i%2==0?bird:bird2,key()); advance(30); }
        fails("DAILY_LIMIT",()->points.pet(auth,bird2,key()));
        assertThat(points.wallet(auth).balance()).isEqualTo(10);
        assertThat(points.history(auth,Long.MAX_VALUE)).hasSize(10);
        assertThat(points.wallet(other).balance()).isZero();
    }
    @Test void shanghaiMidnightDoesNotBypassCooldown() {
        time(Instant.parse("2026-10-29T15:59:50Z")); points.pet(auth,bird,key());
        advance(10);
        assertThat(points.wallet(auth).earnedToday()).isZero();
        fails("REWARD_COOLDOWN",()->points.pet(auth,bird,key()));
        advance(20); points.pet(auth,bird,key());
        assertThat(points.wallet(auth).earnedToday()).isEqualTo(1);
        assertThat(points.wallet(auth).balance()).isEqualTo(2);
    }
    @Test void redeemAndEquipPersistAndNeverOverwriteFactorySkin() {
        earn(30);
        String request=key(); var change=points.redeem(auth,"lake_glow",request);
        assertThat(change.entry().delta()).isEqualTo(-30);
        assertThat(points.redeem(auth,"lake_glow",request).replayed()).isTrue();
        fails("SKIN_ALREADY_OWNED",()->points.redeem(auth,"lake_glow",key()));
        assertThat(points.wallet(auth).balance()).isZero();
        assertThat(points.skins(auth).getFirst().owned()).isTrue();
        assertThat(points.equip(auth,bird,"lake_glow").visualKey()).isEqualTo("lake");
        assertThat(points.outfits(auth)).anyMatch(o->"lake_glow".equals(o.skinId()));
        assertThat(registration.mine(auth).getFirst().skinId()).isEqualTo("yanji_blue");
        String again="Bearer "+registration.login("points_owner","password-12345").token();
        assertThat(points.skins(again).getFirst().owned()).isTrue();
        assertThat(points.outfits(again)).anyMatch(o->"lake_glow".equals(o.skinId()));
        points.equip(auth,bird,null);
        assertThat(points.outfits(auth)).allMatch(o->o.skinId()==null);
        var entries=points.history(auth,Long.MAX_VALUE);
        assertThat(entries.stream().mapToInt(PointsService.Entry::delta).sum()).isZero();
        assertThat(points.history(auth,Long.parseLong(entries.getFirst().id()))).hasSize(30);
    }
    @Test void authorizationInsufficientFundsAndInvalidRequests() throws Exception {
        fails("OWNED_BIRD_NOT_FOUND",()->points.pet(other,bird,key()));
        fails("LOGIN_REQUIRED",()->points.wallet(null));
        fails("INSUFFICIENT_POINTS",()->points.redeem(auth,"lake_glow",key()));
        fails("SKIN_NOT_FOUND",()->points.redeem(auth,"unknown",key()));
        fails("SKIN_NOT_OWNED",()->points.equip(auth,bird,"lake_glow"));
        fails("OWNED_BIRD_NOT_FOUND",()->points.equip(other,bird,null));
        assertThat(points.history(auth,Long.MAX_VALUE)).isEmpty();
        mvc.perform(post("/api/birds/"+bird+"/pet").header("Authorization",auth).contentType("application/json").content("{\"requestId\":\"bad\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/points/ledger").header("Authorization",auth).param("beforeId","bad"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/points")).andExpect(status().isUnauthorized());
    }
    @Test void failedSettlementRollsBackRewardCounter() {
        jdbc.update("UPDATE app_user SET points_balance=? WHERE id=?",Long.MAX_VALUE,user);
        assertThatThrownBy(()->points.pet(auth,bird,key())).isInstanceOf(ArithmeticException.class);
        assertThat(points.wallet(auth).earnedToday()).isZero();
        assertThat(points.wallet(auth).nextRewardAt()).isNull();
        assertThat(points.history(auth,Long.MAX_VALUE)).isEmpty();
        assertThat(points.wallet(auth).balance()).isEqualTo(Long.MAX_VALUE);
    }
    @Test void concurrentRewardsOnlyPayOnce() throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var tasks=List.of(bird,bird2).stream().map(id->executor.submit(()->{
                start.await();
                try { points.pet(auth,id,key()); return true; }
                catch(RegistrationService.Failure e) { assertThat(e.code).isEqualTo("REWARD_COOLDOWN"); return false; }
            })).toList();
            start.countDown(); int winners=0;
            for(var task:tasks) if(task.get(10,TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(points.wallet(auth).balance()).isEqualTo(1);
    }
    @Test void concurrentDuplicateRedemptionsChargeOnce() throws Exception {
        earn(30); CountDownLatch start=new CountDownLatch(1); String request=key();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var tasks=List.of(1,2).stream().map(n->executor.submit(()->{ start.await(); return points.redeem(auth,"lake_glow",request); })).toList();
            start.countDown(); int replayed=0;
            for(var task:tasks) if(task.get(10,TimeUnit.SECONDS).replayed()) replayed++;
            assertThat(replayed).isEqualTo(1);
        }
        assertThat(points.wallet(auth).balance()).isZero();
        assertThat(points.history(auth,Long.MAX_VALUE)).filteredOn(e->e.operation().equals("REDEEM")).hasSize(1);
    }
    @Test void concurrentDifferentPurchasesCannotOverdraw() throws Exception {
        earn(70); CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var tasks=List.of("lake_glow","sunset_glow").stream().map(id->executor.submit(()->{
                start.await(); try { points.redeem(auth,id,key()); return true; }
                catch(RegistrationService.Failure e) { assertThat(e.code).isEqualTo("INSUFFICIENT_POINTS"); return false; }
            })).toList();
            start.countDown(); int winners=0;
            for(var task:tasks) if(task.get(10,TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(points.wallet(auth).balance()).isGreaterThanOrEqualTo(0);
        // Read all ledger pages to reconcile all 70 rewards and the debit.
        long before=Long.MAX_VALUE; long sum=0;
        while(true) { var page=points.history(auth,before); if(page.isEmpty()) break;
            sum+=page.stream().mapToInt(PointsService.Entry::delta).sum(); before=Long.parseLong(page.getLast().id()); }
        assertThat(sum).isEqualTo(points.wallet(auth).balance());
    }
}
