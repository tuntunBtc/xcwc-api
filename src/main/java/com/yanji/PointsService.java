package com.yanji;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Configuration
class PointsClockConfiguration {
    @Bean Clock pointsClock() { return Clock.systemUTC(); }
}

@Service
public class PointsService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final RegistrationService registration;
    private final Clock clock;
    public PointsService(JdbcTemplate jdbc, RegistrationService registration, Clock clock) {
        this.jdbc = jdbc; this.registration = registration; this.clock = clock;
    }
    public record Wallet(long balance, LocalDate date, int earnedToday, int dailyLimit, Instant nextRewardAt) {}
    public record Entry(String id, String operation, String targetId, int delta, long balanceAfter, Instant createdAt) {}
    public record Change(Entry entry, boolean replayed) {}
    public record Skin(String id, String name, int price, String visualKey, boolean owned) {}
    public record Outfit(String birdId, String skinId, String visualKey) {}
    private record Account(long balance, LocalDate rewardDate, int count, Instant lastReward) {}
    private RegistrationService.Failure fail(HttpStatus status, String code, String message) {
        return new RegistrationService.Failure(status, code, message);
    }
    private Account account(String user, boolean lock) {
        return jdbc.queryForObject("SELECT points_balance,pet_reward_date,pet_reward_count,last_pet_reward_at FROM app_user WHERE id=?" + (lock ? " FOR UPDATE" : ""), (r,n) ->
            new Account(r.getLong(1), r.getObject(2, LocalDate.class), r.getInt(3), r.getTimestamp(4) == null ? null : r.getTimestamp(4).toInstant()), user);
    }
    private LocalDate today(Instant now) { return now.atZone(SHANGHAI).toLocalDate(); }
    public Wallet wallet(String auth) {
        Account a = account(registration.userId(auth), false);
        Instant now = clock.instant();
        return new Wallet(a.balance(), today(now), today(now).equals(a.rewardDate()) ? a.count() : 0, 10,
            a.lastReward() == null || !a.lastReward().plusSeconds(30).isAfter(now) ? null : a.lastReward().plusSeconds(30));
    }
    private Entry entry(ResultSet r, int n) throws SQLException {
        return new Entry(r.getString("id"), r.getString("operation"), r.getString("target_id"), r.getInt("delta"), r.getLong("balance_after"), r.getTimestamp("created_at").toInstant());
    }
    private Change replay(String user, String requestId, String operation, String target) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}"))
            throw fail(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID", "请求编号须为 16～64 位字母、数字、下划线或短横线");
        // Locking read observes the latest committed entry after waiting on the account lock, including MySQL REPEATABLE READ.
        List<Entry> rows = jdbc.query("SELECT * FROM points_ledger WHERE user_id=? AND request_id=? FOR UPDATE", this::entry, user, requestId);
        if (rows.isEmpty()) return null;
        Entry e = rows.getFirst();
        if (!e.operation().equals(operation) || !e.targetId().equals(target))
            throw fail(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一请求编号不能用于不同操作");
        return new Change(e, true);
    }
    private Change record(String user, String requestId, String operation, String target, int delta, long balance, Instant now) {
        jdbc.update("UPDATE app_user SET points_balance=? WHERE id=?", balance, user);
        jdbc.update("INSERT INTO points_ledger(user_id,request_id,operation,target_id,delta,balance_after,created_at) VALUES(?,?,?,?,?,?,?)",
            user, requestId, operation, target, delta, balance, Timestamp.from(now));
        return new Change(jdbc.queryForObject("SELECT * FROM points_ledger WHERE user_id=? AND request_id=?", this::entry, user, requestId), false);
    }
    private void ownBird(String user, String birdId) {
        // Claim also locks the bird, so ownership is checked using a current read.
        var rows = jdbc.queryForList("SELECT owner_id FROM bird WHERE id=? FOR UPDATE", birdId);
        if (rows.isEmpty() || !user.equals(rows.getFirst().get("owner_id")))
            throw fail(HttpStatus.NOT_FOUND, "OWNED_BIRD_NOT_FOUND", "没有找到属于你的小鸟");
    }
    @Transactional
    public Change pet(String auth, String birdId, String requestId) {
        String user = registration.userId(auth);
        Account a = account(user, true);
        ownBird(user, birdId);
        Change prior = replay(user, requestId, "PET", birdId);
        if (prior != null) return prior;
        Instant now = clock.instant();
        LocalDate day = today(now);
        int count = day.equals(a.rewardDate()) ? a.count() : 0;
        if (count >= 10) throw fail(HttpStatus.CONFLICT, "DAILY_LIMIT", "今天的抚摸积分已领满，互动仍可继续");
        if (a.lastReward() != null && a.lastReward().plusSeconds(30).isAfter(now))
            throw fail(HttpStatus.CONFLICT, "REWARD_COOLDOWN", "距离上次奖励不足 30 秒，稍后再领取");
        jdbc.update("UPDATE app_user SET pet_reward_date=?,pet_reward_count=?,last_pet_reward_at=? WHERE id=?",
            day, count + 1, Timestamp.from(now), user);
        return record(user, requestId, "PET", birdId, 1, Math.addExact(a.balance(), 1), now);
    }
    public List<Entry> history(String auth, long beforeId) {
        return jdbc.query("SELECT * FROM points_ledger WHERE user_id=? AND id<? ORDER BY id DESC LIMIT 50", this::entry, registration.userId(auth), beforeId);
    }
    // Called within the work-save transaction. Checkin rewards have no pet cooldown or daily cap.
    @Transactional
    public Change checkinReward(String user, String workId, String requestId) {
        Account a = account(user, true);
        Change prior = replay(user, requestId, "CHECKIN_REWARD", workId);
        if (prior != null) return prior;
        return record(user, requestId, "CHECKIN_REWARD", workId, 1, Math.addExact(a.balance(), 1), clock.instant());
    }
    public List<Skin> skins(String auth) {
        String user = registration.userId(auth);
        return jdbc.query("SELECT s.*,CASE WHEN u.user_id IS NULL THEN FALSE ELSE TRUE END AS owned FROM skin_catalog s LEFT JOIN user_skin u ON u.skin_id=s.id AND u.user_id=? WHERE s.active=TRUE ORDER BY s.price,s.id",
            (r,n) -> new Skin(r.getString("id"), r.getString("name"), r.getInt("price"), r.getString("visual_key"), r.getBoolean("owned")), user);
    }
    @Transactional
    public Change redeem(String auth, String skinId, String requestId) {
        String user = registration.userId(auth);
        Account a = account(user, true);
        Change prior = replay(user, requestId, "REDEEM", skinId);
        if (prior != null) return prior;
        var skins = jdbc.query("SELECT * FROM skin_catalog WHERE id=? AND active=TRUE FOR UPDATE",
            (r,n) -> new Skin(r.getString("id"), r.getString("name"), r.getInt("price"), r.getString("visual_key"), false), skinId);
        if (skins.isEmpty()) throw fail(HttpStatus.NOT_FOUND, "SKIN_NOT_FOUND", "皮肤不存在或已下架");
        if (!jdbc.queryForList("SELECT skin_id FROM user_skin WHERE user_id=? AND skin_id=? FOR UPDATE", user, skinId).isEmpty())
            throw fail(HttpStatus.CONFLICT, "SKIN_ALREADY_OWNED", "已拥有该皮肤，无需重复兑换");
        Skin skin = skins.getFirst();
        if (a.balance() < skin.price()) throw fail(HttpStatus.CONFLICT, "INSUFFICIENT_POINTS", "积分不足");
        Instant now = clock.instant();
        jdbc.update("INSERT INTO user_skin(user_id,skin_id,unlocked_at) VALUES(?,?,?)", user, skinId, Timestamp.from(now));
        return record(user, requestId, "REDEEM", skinId, -skin.price(), a.balance() - skin.price(), now);
    }
    @Transactional
    public Outfit equip(String auth, String birdId, String skinId) {
        String user = registration.userId(auth);
        account(user, true);
        ownBird(user, birdId);
        if (skinId == null) {
            jdbc.update("UPDATE bird SET equipped_skin_id=NULL WHERE id=?", birdId);
            return new Outfit(birdId, null, null);
        }
        var rows = jdbc.query("SELECT s.visual_key FROM user_skin u JOIN skin_catalog s ON s.id=u.skin_id WHERE u.user_id=? AND u.skin_id=? FOR UPDATE",
            (r,n) -> r.getString(1), user, skinId);
        if (rows.isEmpty()) throw fail(HttpStatus.CONFLICT, "SKIN_NOT_OWNED", "请先兑换该皮肤");
        jdbc.update("UPDATE bird SET equipped_skin_id=? WHERE id=?", skinId, birdId);
        return new Outfit(birdId, skinId, rows.getFirst());
    }
    public List<Outfit> outfits(String auth) {
        return jdbc.query("SELECT b.id,b.equipped_skin_id,s.visual_key FROM bird b LEFT JOIN skin_catalog s ON s.id=b.equipped_skin_id WHERE b.owner_id=? ORDER BY b.id",
            (r,n) -> new Outfit(r.getString(1), r.getString(2), r.getString(3)), registration.userId(auth));
    }
}
