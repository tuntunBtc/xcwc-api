package com.yanji;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Pure normal-mode settlement; callers persist cursors atomically with business operations. */
final class PetDecay {
    private PetDecay() {}

    record State(int satiety, int hydration, int mood, Instant bodyAt, Instant moodAt,
                 Instant companionAt) {}

    static State settle(State state, Instant now) {
        long hours = Math.max(0, ChronoUnit.HOURS.between(state.bodyAt(), now));
        Instant protectedUntil = state.companionAt().plus(24, ChronoUnit.HOURS);
        Instant moodStart = state.moodAt().isAfter(protectedUntil) ? state.moodAt() : protectedUntil;
        long moodPeriods = Math.max(0, ChronoUnit.HOURS.between(moodStart, now) / 3);
        Instant moodAt = moodPeriods > 0 ? moodStart.plus(moodPeriods * 3, ChronoUnit.HOURS) : state.moodAt();
        return new State((int) Math.max(0, state.satiety() - hours),
            (int) Math.max(0, state.hydration() - hours),
            (int) Math.max(20, state.mood() - moodPeriods),
            state.bodyAt().plus(hours, ChronoUnit.HOURS), moodAt, state.companionAt());
    }
}
