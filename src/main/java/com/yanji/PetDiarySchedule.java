package com.yanji;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.diary", name = "daily-generation-enabled", havingValue = "true", matchIfMissing = true)
public class PetDiarySchedule {
    private static final Logger LOG = LoggerFactory.getLogger(PetDiarySchedule.class);
    private static final int BATCH_SIZE = 100;
    private final PetDiaryService diaries;

    public PetDiarySchedule(PetDiaryService diaries) {
        this.diaries = diaries;
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 60_000)
    public void generateToday() {
        LocalDate today = diaries.today();
        for (String pet : diaries.petsMissingDiary(today, BATCH_SIZE)) {
            try {
                diaries.generateForSystem(pet, today);
            } catch (RuntimeException error) {
                // Failures remain eligible for a later run. Do not expose pet identifiers or private text in logs.
                LOG.warn("Could not generate a daily diary, will retry: {}", error.getClass().getSimpleName());
            }
        }
    }
}
