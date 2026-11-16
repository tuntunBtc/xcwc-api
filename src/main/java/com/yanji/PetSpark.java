package com.yanji;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PetSpark {
    static final String RULE_VERSION = "spark-v1";
    private final JdbcTemplate jdbc;

    public PetSpark(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    enum Status { UNLIT, ACTIVE, RECOVERABLE, RECOVERING, EXPIRED }
    enum Event { LIT, LEVELED, RECOVERY_STARTED, RECOVERY_PROGRESS, RESTORED, RELIT }

    public record Snapshot(Status status, long level, long affinity, boolean effectiveToday,
                           long frozenLevel, int recoveryProgress, LocalDate recoveryDeadline) {}

    record State(long level, long affinity, LocalDate normalDate,
                 LocalDate recoveryLast, int recoveryProgress) {
        static final State EMPTY = new State(0, 0, null, null, 0);

        Snapshot snapshot(LocalDate today) {
            if (normalDate == null) return new Snapshot(Status.UNLIT, 0, 0, false, 0, 0, null);
            if (!today.isAfter(normalDate.plusDays(1))) {
                return new Snapshot(Status.ACTIVE, level, affinity, today.equals(normalDate), 0, 0, null);
            }
            // Gray starts on L+2, so L+W+1 is the last included start date.
            LocalDate deadline = normalDate.plusDays(windowDays(level) + 1L);
            if (recoveryLast != null && !today.isAfter(recoveryLast.plusDays(1))) {
                return new Snapshot(Status.RECOVERING, level, affinity, today.equals(recoveryLast),
                    level, recoveryProgress, deadline);
            }
            if (!today.isAfter(deadline)) {
                return new Snapshot(Status.RECOVERABLE, level, affinity, false, level, 0, deadline);
            }
            return new Snapshot(Status.EXPIRED, 0, affinity, false, 0, 0, null);
        }

        Transition apply(LocalDate today) {
            LocalDate last = recoveryLast != null ? recoveryLast : normalDate;
            if (last != null && !today.isAfter(last)) return new Transition(this, null);
            long nextAffinity = Math.addExact(affinity, 1);
            return switch (snapshot(today).status()) {
                case UNLIT -> new Transition(new State(1, nextAffinity, today, null, 0), Event.LIT);
                case EXPIRED -> new Transition(new State(1, nextAffinity, today, null, 0), Event.RELIT);
                case ACTIVE -> new Transition(new State(Math.addExact(level, 1), nextAffinity, today, null, 0), Event.LEVELED);
                case RECOVERABLE -> new Transition(new State(level, nextAffinity, normalDate, today, 1), Event.RECOVERY_STARTED);
                case RECOVERING -> recoveryProgress == 2
                    ? new Transition(new State(Math.addExact(level, 3), nextAffinity, today, null, 0), Event.RESTORED)
                    : new Transition(new State(level, nextAffinity, normalDate, today, 2), Event.RECOVERY_PROGRESS);
            };
        }
    }

    record Transition(State state, Event event) {}

    static int windowDays(long level) {
        if (level < 7) return 3;
        if (level < 30) return 5;
        if (level < 100) return 7;
        return 10;
    }

    private State read(String petId) {
        return jdbc.query("SELECT level,affinity,normal_date,recovery_last_date,recovery_progress FROM pet_spark WHERE binding_id=?",
            (rs, row) -> new State(rs.getLong("level"), rs.getLong("affinity"),
                rs.getObject("normal_date", LocalDate.class), rs.getObject("recovery_last_date", LocalDate.class),
                rs.getInt("recovery_progress")), petId).stream().findFirst().orElse(State.EMPTY);
    }

    Snapshot snapshot(String petId, LocalDate today) {
        return read(petId).snapshot(today);
    }

    // The caller holds the account and binding locks in the care transaction.
    Event record(String petId, LocalDate today, String requestId) {
        State previous = read(petId);
        Transition transition = previous.apply(today);
        if (transition.event() == null) return null;
        State next = transition.state();
        if (previous.normalDate() == null) {
            jdbc.update("INSERT INTO pet_spark(binding_id,level,affinity,normal_date,recovery_last_date,recovery_progress) VALUES(?,?,?,?,?,?)",
                petId, next.level(), next.affinity(), next.normalDate(), next.recoveryLast(), next.recoveryProgress());
        } else {
            jdbc.update("UPDATE pet_spark SET level=?,affinity=?,normal_date=?,recovery_last_date=?,recovery_progress=? WHERE binding_id=?",
                next.level(), next.affinity(), next.normalDate(), next.recoveryLast(), next.recoveryProgress(), petId);
        }
        jdbc.update("INSERT INTO pet_spark_day(binding_id,business_date,request_id) VALUES(?,?,?)",
            petId, today, requestId.getBytes(StandardCharsets.US_ASCII));
        return transition.event();
    }
}
