package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
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
class PetDiaryTest {
    @Autowired RegistrationService users;
    @Autowired CloudPetService pets;
    @Autowired PetDiaryService diaries;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean Clock clock;
    private Instant now;
    private String authorization;
    private String bird;
    private String pet;

    @BeforeEach
    void createPet() {
        now = Instant.parse("2026-11-27T15:59:59Z");
        when(clock.instant()).thenAnswer(invocation -> now);
        authorization = newAccount();
        bird = users.provision().id();
        pet = adopt(authorization, bird);
    }

    @Test
    void readsDoNotGenerateAndDailyJobUsesBeijingDateWithoutBackfill() throws Exception {
        mvc.perform(get(resource()).header("Authorization", authorization)
            .param("from", "2026-11-22").param("to", "2026-12-03"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.today").value("2026-11-27"))
            .andExpect(jsonPath("$.days").isEmpty());
        assertThat(diaries.petsMissingDiary(diaries.today(), 100)).contains(pet);
        diaries.generateForSystem(pet, diaries.today());
        mvc.perform(post(resource() + "/today").header("Authorization", authorization))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.date").value("2026-11-27"));

        now = now.plusSeconds(2);
        assertThat(diaries.today()).isEqualTo(LocalDate.parse("2026-11-28"));
        assertThat(diaries.petsMissingDiary(diaries.today(), 100)).contains(pet);
        diaries.generateForSystem(pet, diaries.today());
        diaries.generateForSystem(pet, diaries.today());
        assertThat(diaries.calendar(authorization, pet, LocalDate.parse("2026-11-26"), diaries.today()).days())
            .extracting(PetDiaryService.DaySummary::date)
            .containsExactly(LocalDate.parse("2026-11-27"), LocalDate.parse("2026-11-28"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pet_diary_day WHERE binding_id = ?", Integer.class, pet))
            .isEqualTo(2);
    }

    @Test
    void trustedActivityCanArriveLateAndSharesTheExistingDailyPage() {
        var initial = diaries.ensureToday(authorization, pet);
        var date = initial.date();
        now = now.plusSeconds(2);
        String travelId = id();
        diaries.recordActivity(pet, travelId, date, "travel", "去湖边", "沿着湖边看了船。");
        diaries.recordActivity(pet, travelId, date, "travel", "去湖边", "沿着湖边看了船。");
        now = now.plusSeconds(1);
        diaries.recordActivity(pet, id(), date, "study", "学听雨", "辨认雨落在叶子上的声音。");
        assertThatThrownBy(() -> diaries.recordActivity(pet, travelId, date, "travel", "其他活动", "内容更改"))
            .isInstanceOfSatisfying(RegistrationService.Failure.class, error -> assertThat(error.code).isEqualTo("IDEMPOTENCY_CONFLICT"));
        var entry = diaries.day(authorization, pet, date);
        assertThat(entry.title()).isEqualTo(initial.title());
        assertThat(entry.text()).isEqualTo(initial.text());
        assertThat(entry.activities()).extracting(PetDiaryService.Activity::kind).containsExactly("travel", "study");
        assertThat(diaries.calendar(authorization, pet, date, date).days().getFirst().activities()).isEqualTo(entry.activities());

        var reply = diaries.leaveLetter(authorization, pet, date, new PetDiaryService.LetterInput(id(), "旅行好玩吗？"));
        assertThat(reply.letters().getFirst().reply()).contains("去湖边").doesNotContain("学听雨");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pet_diary_day WHERE binding_id = ?", Integer.class, pet)).isEqualTo(1);
    }

    @Test
    void concurrentRetriesCreateOneReplyAndPrivateReadsDoNotChangeUnread() throws Exception {
        LocalDate date = diaries.ensureToday(authorization, pet).date();
        String requestId = id();
        var input = new PetDiaryService.LetterInput(requestId, "晚安，小鸟");
        var ready = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var write = (java.util.concurrent.Callable<PetDiaryService.Day>) () -> {
                ready.await();
                return diaries.leaveLetter(authorization, pet, date, input);
            };
            var first = pool.submit(write);
            var second = pool.submit(write);
            ready.countDown();
            var day = first.get(15, TimeUnit.SECONDS);
            var replay = second.get(15, TimeUnit.SECONDS);
            assertThat(day.letters()).hasSize(1).isEqualTo(replay.letters());
            assertThat(day.unreadCount()).isEqualTo(1);
            String letterId = day.letters().getFirst().id();
            String otherAuth = newAccount();
            String otherPet = adopt(otherAuth, bird);
            diaries.ensureToday(otherAuth, otherPet);

            mvc.perform(get(resource() + "/" + date).header("Authorization", otherAuth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PET_NOT_FOUND"));
            mvc.perform(post("/bird/v1/me/pets/" + otherPet + "/diary/" + date + "/letters/" + letterId + "/read")
                .header("Authorization", otherAuth)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DIARY_LETTER_NOT_FOUND"));
            mvc.perform(get(resource() + "/" + date).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
            assertThat(diaries.calendar(authorization, pet, date, date).days().getFirst().unreadCount()).isEqualTo(1);
            mvc.perform(post(resource() + "/" + date + "/letters").header("Authorization", authorization)
                .contentType("application/json").content(json.writeValueAsString(Map.of("requestId", requestId, "text", "不同的内容"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
            var read = diaries.markRead(authorization, pet, date, letterId);
            now = now.plusSeconds(1);
            assertThat(diaries.markRead(authorization, pet, date, letterId).letters()).isEqualTo(read.letters());
            assertThat(read.letters().getFirst().readAt()).isNotNull();
            assertThat(read.unreadCount()).isZero();
            assertThat(diaries.calendar(authorization, pet, date, date).days().getFirst().unreadCount()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidInputsAndInactiveBirds() throws Exception {
        var date = diaries.ensureToday(authorization, pet).date();
        mvc.perform(get(resource() + "/" + date)).andExpect(status().isUnauthorized());
        mvc.perform(get(resource()).header("Authorization", authorization)
            .param("from", "2026-01-01").param("to", "2026-12-31"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DIARY_RANGE"));
        mvc.perform(get(resource()).header("Authorization", authorization).param("to", date.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DIARY_RANGE"));
        mvc.perform(post(resource() + "/" + date + "/letters").header("Authorization", authorization)
            .contentType("application/json").content(json.writeValueAsString(Map.of("requestId", id(), "text", "鸟".repeat(501)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DIARY_TEXT"));
        jdbc.update("UPDATE bird SET active = FALSE WHERE id = ?", bird);
        mvc.perform(post(resource() + "/today").header("Authorization", authorization))
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("BIRD_INACTIVE"));
    }

    private String newAccount() {
        return "Bearer " + users.register("diary_" + users.randomKey(6), "fixture-password-123").token();
    }

    private String adopt(String auth, String birdId) {
        var result = pets.adopt(auth, new CloudPetService.Adoption(id(), birdId, "测试小鸟"));
        return (String) ((Map<?, ?>) result.get("pet")).get("petId");
    }

    private String resource() {
        return "/bird/v1/me/pets/" + pet + "/diary";
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
