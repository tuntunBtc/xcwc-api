package com.yanji;

import static com.yanji.PetSpark.Event.*;
import static com.yanji.PetSpark.Status.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PetSparkTest {
    private static final LocalDate LAST = LocalDate.of(2026, 11, 16);

    private PetSpark.State active(long level) {
        return new PetSpark.State(level, level, LAST, null, 0);
    }

    @Test void startsOnFirstActionAndOnlyCountsTheFirstActionEachDay() {
        var empty = PetSpark.State.EMPTY.snapshot(LAST);
        assertThat(empty.status()).isEqualTo(UNLIT);
        assertThat(empty.affinity()).isZero();
        var first = PetSpark.State.EMPTY.apply(LAST);
        assertThat(first.event()).isEqualTo(LIT);
        assertThat(first.state().snapshot(LAST).effectiveToday()).isTrue();
        assertThat(first.state().snapshot(LAST.plusDays(1)).effectiveToday()).isFalse();
        var repeat = first.state().apply(LAST);
        assertThat(repeat.event()).isNull();
        assertThat(repeat.state()).isEqualTo(first.state());
        var next = first.state().apply(LAST.plusDays(1));
        assertThat(next.event()).isEqualTo(LEVELED);
        assertThat(next.state().level()).isEqualTo(2);
        assertThat(next.state().affinity()).isEqualTo(2);
    }

    @Test void windowUsesFrozenLevelAndBothEndpointsAreInclusive() {
        var levels = List.of(1, 6, 7, 29, 30, 99, 100, 365);
        var windows = List.of(3, 3, 5, 5, 7, 7, 10, 10);
        for (int i = 0; i < levels.size(); i++) {
            var state = active(levels.get(i));
            var gray = LAST.plusDays(2);
            var end = gray.plusDays(windows.get(i) - 1);
            assertThat(state.snapshot(gray.minusDays(1)).status()).isEqualTo(ACTIVE);
            for (var date : List.of(gray, end)) {
                var snapshot = state.snapshot(date);
                assertThat(snapshot.status()).isEqualTo(RECOVERABLE);
                assertThat(snapshot.frozenLevel()).isEqualTo(levels.get(i).longValue());
                assertThat(snapshot.recoveryDeadline()).isEqualTo(end);
                assertThat(state.apply(date).event()).isEqualTo(RECOVERY_STARTED);
            }
            var expired = state.snapshot(end.plusDays(1));
            assertThat(expired.status()).isEqualTo(EXPIRED);
            assertThat(expired.frozenLevel()).isZero();
            assertThat(expired.affinity()).isEqualTo(levels.get(i).longValue());
            assertThat(state.apply(end.plusDays(1)).event()).isEqualTo(RELIT);
        }
    }

    @Test void lastDayStartCanFinishAfterDeadlineAndResetsTheAnchor() {
        var state = active(5);
        var end = LAST.plusDays(4);
        var first = state.apply(end);
        var second = first.state().apply(end.plusDays(1));
        assertThat(second.event()).isEqualTo(RECOVERY_PROGRESS);
        var snapshot = second.state().snapshot(end.plusDays(1));
        assertThat(snapshot.status()).isEqualTo(RECOVERING);
        assertThat(snapshot.recoveryProgress()).isEqualTo(2);
        assertThat(snapshot.recoveryDeadline()).isEqualTo(end);
        assertThat(snapshot.effectiveToday()).isTrue();
        assertThat(second.state().apply(end.plusDays(1)).event()).isNull();
        var restored = second.state().apply(end.plusDays(2));
        assertThat(restored.event()).isEqualTo(RESTORED);
        assertThat(restored.state()).isEqualTo(new PetSpark.State(8, 8, end.plusDays(2), null, 0));
        assertThat(restored.state().apply(end.plusDays(2)).event()).isNull();
        assertThat(restored.state().apply(end.plusDays(3)).state().level()).isEqualTo(9);
        assertThat(restored.state().snapshot(end.plusDays(4)).recoveryDeadline()).isEqualTo(end.plusDays(8));
    }

    @Test void retryDoesNotExtendWindowAndFailedDaysOnlyAddAffinity() {
        var state = active(30);
        var gray = LAST.plusDays(2);
        var attempt = state.apply(gray).state();
        assertThat(attempt.snapshot(gray.plusDays(1)).status()).isEqualTo(RECOVERING);
        assertThat(attempt.snapshot(gray.plusDays(2)).status()).isEqualTo(RECOVERABLE);
        assertThat(attempt.snapshot(gray.plusDays(2)).recoveryProgress()).isZero();
        assertThat(attempt.snapshot(gray.plusDays(2)).recoveryDeadline()).isEqualTo(gray.plusDays(6));
        var retry = attempt.apply(gray.plusDays(2));
        assertThat(retry.event()).isEqualTo(RECOVERY_STARTED);
        assertThat(retry.state().snapshot(gray.plusDays(2)).recoveryProgress()).isEqualTo(1);
        var restored = retry.state().apply(gray.plusDays(3)).state().apply(gray.plusDays(4));
        assertThat(restored.event()).isEqualTo(RESTORED);
        assertThat(restored.state().level()).isEqualTo(33);
        assertThat(restored.state().affinity()).isEqualTo(34);
    }

    @Test void expiryAfterABrokenRecoveryRestartsAtOneWithLifetimeAffinity() {
        var end = LAST.plusDays(4);
        var first = active(5).apply(end).state();
        assertThat(first.snapshot(end.plusDays(1)).status()).isEqualTo(RECOVERING);
        assertThat(first.snapshot(end.plusDays(2)).status()).isEqualTo(EXPIRED);
        assertThat(first.snapshot(end.plusDays(2)).recoveryDeadline()).isNull();
        var restarted = first.apply(end.plusDays(2));
        assertThat(restarted.state()).isEqualTo(new PetSpark.State(1, 7, end.plusDays(2), null, 0));
    }

    @Test void levelHasNoProductCeilingAndRecoveryCanCrossAnyTier() {
        for (int level : List.of(6, 29, 99, 100, 365)) {
            assertThat(active(level).apply(LAST.plusDays(1)).state().level()).isEqualTo(level + 1);
        }
        for (int level : List.of(5, 28, 98)) {
            var first = active(level).apply(LAST.plusDays(2));
            var third = first.state().apply(LAST.plusDays(3)).state().apply(LAST.plusDays(4));
            assertThat(third.state().level()).isEqualTo(level + 3);
        }
    }
}
