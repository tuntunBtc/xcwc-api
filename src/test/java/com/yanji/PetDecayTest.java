package com.yanji;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class PetDecayTest {
    private final Instant start = Instant.parse("2026-11-11T00:00:00Z");
    private PetDecay.State initial() {
        return new PetDecay.State(60, 60, 80, start, start, start);
    }

    @Test void normalModeProtectsMoodAndClampsLongAbsence() {
        var firstDay = PetDecay.settle(initial(), start.plus(24, ChronoUnit.HOURS));
        assertThat(firstDay.satiety()).isEqualTo(36);
        assertThat(firstDay.hydration()).isEqualTo(36);
        assertThat(firstDay.mood()).isEqualTo(80);
        var secondDay = PetDecay.settle(firstDay, start.plus(48, ChronoUnit.HOURS));
        assertThat(secondDay.satiety()).isEqualTo(12);
        assertThat(secondDay.mood()).isEqualTo(72);
        var late = PetDecay.settle(secondDay, start.plus(365, ChronoUnit.DAYS));
        assertThat(late.satiety()).isZero();
        assertThat(late.hydration()).isZero();
        assertThat(late.mood()).isEqualTo(20);
    }

    @Test void repeatedReadsPreserveRemaindersAndNeverDoubleSettle() {
        Instant end = start.plus(53, ChronoUnit.HOURS).plusSeconds(3599);
        var intermediate = PetDecay.settle(initial(), start.plus(29, ChronoUnit.HOURS).plusSeconds(1700));
        var result = PetDecay.settle(intermediate, end);
        assertThat(result).isEqualTo(PetDecay.settle(initial(), end));
        assertThat(PetDecay.settle(result, end)).isEqualTo(result);
        assertThat(result.bodyAt()).isEqualTo(start.plus(53, ChronoUnit.HOURS));
    }

    @Test void ClockGoingBackDoesNotGrantStatsOrMoveCursorsBack() {
        assertThat(PetDecay.settle(initial(), start.minusSeconds(3600))).isEqualTo(initial());
    }
}
