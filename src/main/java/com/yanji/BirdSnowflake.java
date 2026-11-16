package com.yanji;

import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 41 timestamp bits + 10 node bits (fixed 0) + 12 sequence bits.
 * Database row serialization makes allocation safe across threads, replicas and restarts.
 */
@Service
public class BirdSnowflake {
    static final long EPOCH_MILLIS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate reservation;
    public BirdSnowflake(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.clock = clock;
        reservation = new TransactionTemplate(manager);
        reservation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public String nextId() {
        return reservation.execute(status -> {
            long[] state = jdbc.queryForObject("SELECT last_millis,sequence_no FROM bird_snowflake_state WHERE allocator_id=1 FOR UPDATE",
                (r,n) -> new long[]{r.getLong(1),r.getInt(2)});
            long now = clock.millis();
            long elapsed = now - EPOCH_MILLIS;
            if (now < state[0]) throw unavailable("SNOWFLAKE_CLOCK_ROLLBACK", "系统时钟回退，暂停生成小鸟编码，请校准时间后重试");
            if (elapsed <= 0 || elapsed >= (1L << 41)) throw unavailable("SNOWFLAKE_TIME_RANGE", "系统时间超出小鸟编码支持范围");
            long sequence = now == state[0] ? state[1] + 1 : 0;
            if (sequence > 4095) throw unavailable("SNOWFLAKE_SEQUENCE_EXHAUSTED", "本毫秒发号额度已满，请稍后重试");
            long id = (elapsed << 22) | sequence;
            jdbc.update("UPDATE bird_snowflake_state SET last_millis=?,sequence_no=? WHERE allocator_id=1", now, sequence);
            return Long.toString(id);
        });
    }
    private RegistrationService.Failure unavailable(String code, String message) {
        return new RegistrationService.Failure(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
