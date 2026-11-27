package com.yanji;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetDiaryService {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String BINDING = """
        SELECT bb.user_id, bb.hatched_at, b.active FROM bird_binding bb JOIN bird b ON b.id = bb.bird_id
        WHERE bb.id = ?
        """;

    private final JdbcTemplate jdbc;
    private final RegistrationService users;
    private final Clock clock;

    public PetDiaryService(JdbcTemplate jdbc, RegistrationService users, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.clock = clock;
    }

    public record Activity(String kind, String title, String text) {}
    public record Letter(String id, String text, String reply, Instant createdAt, Instant readAt) {}
    public record Day(LocalDate date, String kind, String mood, String title, String text,
                      List<Activity> activities, List<Letter> letters, long unreadCount) {}
    public record DaySummary(LocalDate date, String kind, String mood, List<Activity> activities, long unreadCount) {}
    public record Calendar(LocalDate today, List<DaySummary> days) {}
    public record LetterInput(String requestId, String text) {}

    private record Binding(String owner, LocalDate hatched, boolean active) {}
    private record Daily(LocalDate date, String mood, String title, String text) {}
    private record DatedActivity(LocalDate date, Activity value) {}
    private record DatedLetter(LocalDate date, String text) {}
    private record Care(String mood, List<String> facts) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Calendar calendar(String authorization, String petId, LocalDate from, LocalDate to) {
        ownerBinding(authorization, petId, false);
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 62) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_DIARY_RANGE", "日期范围最多包含 62 天");
        }
        LocalDate today = today();
        LocalDate end = to.isAfter(today) ? today : to;
        List<Daily> rows = jdbc.query("""
            SELECT diary_date, mood, title, diary_text FROM pet_diary_day
            WHERE binding_id = ? AND diary_date BETWEEN ? AND ? ORDER BY diary_date
            """, (result, index) -> daily(result), petId, from, end);
        if (rows.isEmpty()) return new Calendar(today, List.of());

        Map<LocalDate, List<Activity>> activitiesByDate = new LinkedHashMap<>();
        for (DatedActivity entry : activityRows(petId, from, end)) {
            activitiesByDate.computeIfAbsent(entry.date(), date -> new ArrayList<>()).add(entry.value());
        }
        Map<LocalDate, Long> unreadByDate = new LinkedHashMap<>();
        jdbc.query("""
            SELECT diary_date, COUNT(*) AS unread_count FROM pet_diary_letter
            WHERE binding_id = ? AND diary_date BETWEEN ? AND ? AND read_at IS NULL
            GROUP BY diary_date
            """, result -> {
            unreadByDate.put(result.getObject("diary_date", LocalDate.class), result.getLong("unread_count"));
        }, petId, from, end);
        List<DaySummary> summaries = rows.stream().map(row -> {
            List<Activity> activities = activitiesByDate.getOrDefault(row.date(), List.of());
            return new DaySummary(row.date(), kind(activities), mood(row, activities), activities,
                unreadByDate.getOrDefault(row.date(), 0L));
        }).toList();
        return new Calendar(today, summaries);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Day day(String authorization, String petId, LocalDate date) {
        ownerBinding(authorization, petId, false);
        return details(petId, date);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Day ensureToday(String authorization, String petId) {
        Binding binding = ownerBinding(authorization, petId, true);
        Instant now = clock.instant();
        LocalDate date = now.atZone(BUSINESS_ZONE).toLocalDate();
        ensurePage(petId, binding, date, now);
        return details(petId, date);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Day leaveLetter(String authorization, String petId, LocalDate date, LetterInput input) {
        ownerBinding(authorization, petId, true);
        String request = input == null ? null : input.requestId();
        byte[] requestId = requestKey(request);
        String text = cleanText(input.text(), 500);

        List<DatedLetter> previous = jdbc.query("""
            SELECT diary_date, letter_text FROM pet_diary_letter WHERE binding_id = ? AND request_id = ?
            """, (result, index) -> new DatedLetter(result.getObject("diary_date", LocalDate.class),
                result.getString("letter_text")), petId, requestId);
        if (!previous.isEmpty()) {
            DatedLetter saved = previous.getFirst();
            if (!saved.date().equals(date) || !saved.text().equals(text)) {
                throw failure(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "这条留言的请求标识已用于其他内容");
            }
            return details(petId, date);
        }

        Day diary = details(petId, date);
        jdbc.update("""
            INSERT INTO pet_diary_letter(id, binding_id, diary_date, request_id, letter_text, reply, created_at)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            """, users.randomKey(16), petId, date, requestId, text, replyTo(diary, text), Timestamp.from(clock.instant()));
        return details(petId, date);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Day markRead(String authorization, String petId, LocalDate date, String letterId) {
        ownerBinding(authorization, petId, true);
        int matching = jdbc.update("""
            UPDATE pet_diary_letter SET read_at = COALESCE(read_at, ?)
            WHERE id = ? AND binding_id = ? AND diary_date = ?
            """, Timestamp.from(clock.instant()), letterId, petId, date);
        if (matching == 0 && Boolean.FALSE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM pet_diary_letter WHERE id = ? AND binding_id = ? AND diary_date = ?)
            """, Boolean.class, letterId, petId, date))) {
            throw failure(HttpStatus.NOT_FOUND, "DIARY_LETTER_NOT_FOUND", "这封回信不存在");
        }
        return details(petId, date);
    }

    /** Accepts an activity from a trusted server workflow, identified by its stable event ID. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordActivity(String petId, String sourceId, LocalDate date, String kind, String title, String text) {
        Binding binding = binding(petId, null, true);
        Instant now = clock.instant();
        if (date == null || date.isAfter(now.atZone(BUSINESS_ZONE).toLocalDate())) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_DIARY_DATE", "活动日期不能在未来");
        }
        if (!"travel".equals(kind) && !"study".equals(kind)) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_DIARY_ACTIVITY", "只接受旅行或学习活动");
        }
        byte[] source = requestKey(sourceId);
        Activity content = new Activity(kind, cleanText(title, 120), cleanText(text, 1000));
        List<DatedActivity> saved = jdbc.query("""
            SELECT diary_date, kind, title, activity_text FROM pet_diary_activity WHERE binding_id = ? AND source_id = ?
            """, (result, index) -> activity(result), petId, source);
        if (!saved.isEmpty()) {
            if (!saved.getFirst().equals(new DatedActivity(date, content))) {
                throw failure(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "活动编号已用于其他记录");
            }
            return;
        }
        ensurePage(petId, binding, date, now);
        jdbc.update("""
            INSERT INTO pet_diary_activity(binding_id, diary_date, source_id, kind, title, activity_text, created_at)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            """, petId, date, source, content.kind(), content.title(), content.text(), Timestamp.from(now));
    }

    LocalDate today() {
        return clock.instant().atZone(BUSINESS_ZONE).toLocalDate();
    }

    List<String> petsMissingDiary(LocalDate date, int limit) {
        return jdbc.queryForList("""
            SELECT bb.id FROM bird_binding bb
            JOIN app_user u ON u.id = bb.user_id AND u.cloud_pet_enabled = TRUE
            JOIN bird b ON b.id = bb.bird_id AND b.active = TRUE
            JOIN pet_state p ON p.binding_id = bb.id
            WHERE bb.hatched_at < ?
              AND NOT EXISTS (SELECT 1 FROM pet_diary_day d WHERE d.binding_id = bb.id AND d.diary_date = ?)
            ORDER BY bb.id LIMIT ?
            """, String.class, Timestamp.from(date.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()), date, limit);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void generateForSystem(String petId, LocalDate date) {
        Binding binding = binding(petId, null, true);
        ensurePage(petId, binding, date, clock.instant());
    }

    private Binding ownerBinding(String authorization, String petId, boolean lock) {
        String owner = users.userId(authorization);
        Binding result = binding(petId, owner, lock);
        if (lock) users.userId(authorization);
        return result;
    }

    private Binding binding(String petId, String owner, boolean lock) {
        if (petId == null || !petId.matches("[0-9a-f]{32}")) throw missingPet();
        String sql = BINDING + (owner == null ? "" : " AND bb.user_id = ?") + (lock ? " FOR UPDATE" : "");
        Object[] arguments = owner == null ? new Object[]{petId} : new Object[]{petId, owner};
        List<Binding> rows = jdbc.query(sql, (result, index) -> new Binding(result.getString("user_id"),
            result.getTimestamp("hatched_at").toInstant().atZone(BUSINESS_ZONE).toLocalDate(),
            result.getBoolean("active")), arguments);
        if (rows.isEmpty()) throw missingPet();
        Binding binding = rows.getFirst();
        if (!binding.active()) throw failure(HttpStatus.GONE, "BIRD_INACTIVE", "这只小鸟已停用");
        return binding;
    }

    private void ensurePage(String petId, Binding binding, LocalDate date, Instant now) {
        if (date.isBefore(binding.hatched())) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_DIARY_DATE", "日记早于小鸟入住日期");
        }
        if (findPage(petId, date) != null) return;
        Care care = careFor(petId, date);
        String title = care.facts().isEmpty() ? switch (date.getDayOfMonth() % 3) {
            case 0 -> "小窝日记";
            case 1 -> "今天留一页";
            default -> "我的小窝手账";
        } : "今天的小窝时光";
        String text = care.facts().isEmpty() ? "这是我留给今天的一页。你想和我说话，可以把话留在这里。"
            : "今天" + String.join("，", care.facts()) + "。我把这段相处记了下来。";
        jdbc.update("""
            INSERT INTO pet_diary_day(binding_id, diary_date, mood, title, diary_text, created_at)
            VALUES(?, ?, ?, ?, ?, ?)
            """, petId, date, care.mood(), title, text, Timestamp.from(now));
    }

    private Care careFor(String petId, LocalDate date) {
        return jdbc.query("""
            SELECT mood_date, mood, pet_count, feed_count, water_count, clean_count FROM pet_state WHERE binding_id = ?
            """, (result, index) -> {
            List<String> facts = new ArrayList<>();
            if (date.equals(result.getObject("mood_date", LocalDate.class))) {
                if (result.getInt("pet_count") > 0) facts.add("主人摸了摸我");
                if (result.getInt("feed_count") > 0) facts.add("我吃了主人送来的食物");
                if (result.getInt("water_count") > 0) facts.add("喝到了主人添的水");
                if (result.getInt("clean_count") > 0) facts.add("主人来照看了小窝");
            }
            return new Care(result.getInt("mood") >= 85 ? "content" : "calm", facts);
        }, petId).stream().findFirst().orElse(new Care("calm", List.of()));
    }

    private Daily findPage(String petId, LocalDate date) {
        return jdbc.query("""
            SELECT diary_date, mood, title, diary_text FROM pet_diary_day WHERE binding_id = ? AND diary_date = ?
            """, (result, index) -> daily(result), petId, date).stream().findFirst().orElse(null);
    }

    private Day details(String petId, LocalDate date) {
        if (date == null || date.isAfter(today())) throw missingDay();
        Daily row = findPage(petId, date);
        if (row == null) throw missingDay();
        List<Activity> activities = activityRows(petId, date, date).stream().map(DatedActivity::value).toList();
        List<Letter> letters = jdbc.query("""
            SELECT id, letter_text, reply, created_at, read_at FROM pet_diary_letter
            WHERE binding_id = ? AND diary_date = ? ORDER BY created_at, id
            """, (result, index) -> {
            Timestamp readAt = result.getTimestamp("read_at");
            return new Letter(result.getString("id"), result.getString("letter_text"), result.getString("reply"),
                result.getTimestamp("created_at").toInstant(), readAt == null ? null : readAt.toInstant());
        }, petId, date);
        return new Day(row.date(), kind(activities), mood(row, activities), row.title(), row.text(), activities, letters,
            letters.stream().filter(letter -> letter.readAt() == null).count());
    }

    private List<DatedActivity> activityRows(String petId, LocalDate start, LocalDate end) {
        return jdbc.query("""
            SELECT diary_date, kind, title, activity_text FROM pet_diary_activity
            WHERE binding_id = ? AND diary_date BETWEEN ? AND ? ORDER BY diary_date, created_at, source_id
            """, (result, index) -> activity(result), petId, start, end);
    }

    private static Daily daily(ResultSet result) throws SQLException {
        return new Daily(result.getObject("diary_date", LocalDate.class), result.getString("mood"),
            result.getString("title"), result.getString("diary_text"));
    }

    private static DatedActivity activity(ResultSet result) throws SQLException {
        return new DatedActivity(result.getObject("diary_date", LocalDate.class), new Activity(result.getString("kind"),
            result.getString("title"), result.getString("activity_text")));
    }

    private static String kind(List<Activity> activities) {
        return activities.isEmpty() ? "home" : activities.getLast().kind();
    }

    private static String mood(Daily row, List<Activity> activities) {
        return switch (kind(activities)) {
            case "travel" -> "curious";
            case "study" -> "proud";
            default -> row.mood();
        };
    }

    private String replyTo(Day diary, String message) {
        if (message.contains("旅行") || message.contains("出去")) return replyAboutActivity(diary, "travel", "旅行");
        if (message.contains("学习") || message.contains("学会")) return replyAboutActivity(diary, "study", "学习");
        if (message.contains("难过") || message.contains("累")) {
            return "我收到你写的话啦。我们先一起静一静，想再说的时候，我就在这一页里听。";
        }
        if (!diary.activities().isEmpty()) {
            Activity activity = diary.activities().getLast();
            return "你的信我收到了。那天我把「" + activity.title() + "」写进手账了，也想听你说说你的这一天。";
        }
        if (message.contains("晚安")) return "晚安！谢谢你来我的小窝说一声。我把这封信收在这一页啦。";
        if (message.contains("谢谢") || "content".equals(diary.mood())) return "看到你的留言，我心里暖暖的。谢谢你来翻我的手账，我也想听听你今天的故事。";
        return "啾，我收到你的留言啦！我把它收在这天的手账里。想继续聊的时候，再来这一页找我。";
    }

    private static String replyAboutActivity(Day diary, String kind, String label) {
        return diary.activities().reversed().stream().filter(activity -> kind.equals(activity.kind())).findFirst()
            .map(activity -> "你问到的" + label + "，我记在「" + activity.title() + "」里啦。谢谢你和我聊这件事。")
            .orElse("我翻了翻这一页，那一天还没有" + label + "记录。有了新见闻，我会把它记进手账。");
    }

    private static byte[] requestKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{16,64}")) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID", "请求标识格式不正确");
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String cleanText(String value, int maximum) {
        if (value == null) throw invalidText(maximum);
        String text = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > maximum
            || text.codePoints().anyMatch(point -> point != '\n' && Character.isISOControl(point))) {
            throw invalidText(maximum);
        }
        return text;
    }

    private static RegistrationService.Failure invalidText(int limit) {
        return failure(HttpStatus.BAD_REQUEST, "INVALID_DIARY_TEXT", "内容不能为空，长度不超过 " + limit + " 字");
    }
    private static RegistrationService.Failure missingPet() {
        return failure(HttpStatus.NOT_FOUND, "PET_NOT_FOUND", "没有找到这只小鸟");
    }
    private static RegistrationService.Failure missingDay() {
        return failure(HttpStatus.NOT_FOUND, "DIARY_NOT_FOUND", "这一天暂无日记");
    }
    private static RegistrationService.Failure failure(HttpStatus status, String code, String message) {
        return new RegistrationService.Failure(status, code, message);
    }
}
