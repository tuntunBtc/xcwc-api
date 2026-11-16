package com.yanji;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> PAGES = List.of("home", "city", "shop", "profile", "adopt", "photo", "wardrobe", "flock");
    private static final int RETENTION_DAYS = 90;
    private final JdbcTemplate jdbc;
    private final AtomicReference<LocalDate> lastCleanupDay = new AtomicReference<>();

    public AnalyticsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Daily(LocalDate date, long visits, long pageViews) {}
    public record PageViews(String page, long pageViews) {}
    public record AgentView(String page, String userAgent, Instant viewedAt) {}
    public record Summary(LocalDate from, LocalDate to, List<Daily> daily, List<PageViews> pages, List<AgentView> recentAgents) {}

    public void record(String page, String sessionId, String userAgent) {
        if (!PAGES.contains(page) || sessionId == null || !sessionId.matches("[a-f0-9]{32}")) return;
        Instant now = Instant.now();
        LocalDate today = now.atZone(BUSINESS_ZONE).toLocalDate();
        cleanup(today);
        String safeAgent = userAgent == null || userAgent.isBlank() ? "(unknown)" : userAgent.substring(0, Math.min(userAgent.length(), 768));
        jdbc.update("INSERT INTO analytics_page_view(id,visit_date,session_hash,page_code,user_agent,viewed_at) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID().toString().replace("-", ""), today,
            RegistrationService.digest(sessionId), page, safeAgent, Timestamp.from(now));
    }

    private void cleanup(LocalDate today) {
        if (today.equals(lastCleanupDay.get())) return;
        synchronized (lastCleanupDay) {
            if (today.equals(lastCleanupDay.get())) return;
            jdbc.update("DELETE FROM analytics_page_view WHERE visit_date < ?", java.sql.Date.valueOf(today.minusDays(RETENTION_DAYS - 1)));
            lastCleanupDay.set(today);
        }
    }

    public Summary summary(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        cleanup(today);
        if (from == null) from = today.minusDays(6);
        if (to == null) to = today;
        if (from.isAfter(to) || from.isBefore(today.minusDays(RETENTION_DAYS - 1)) || to.isAfter(today))
            throw new IllegalArgumentException("日期范围须在最近 90 天内，且结束日期不能晚于今天");
        List<Daily> observedDays = jdbc.query("SELECT visit_date, COUNT(DISTINCT session_hash) AS visits, COUNT(*) AS page_views FROM analytics_page_view WHERE visit_date BETWEEN ? AND ? GROUP BY visit_date ORDER BY visit_date",
            (rs, row) -> new Daily(rs.getDate("visit_date").toLocalDate(), rs.getLong("visits"), rs.getLong("page_views")),
            java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        Map<LocalDate, Daily> byDate = new HashMap<>();
        for (Daily day : observedDays) byDate.put(day.date(), day);
        List<Daily> daily = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1))
            daily.add(byDate.getOrDefault(day, new Daily(day, 0, 0)));
        List<PageViews> pages = jdbc.query("SELECT page_code, COUNT(*) AS page_views FROM analytics_page_view WHERE visit_date BETWEEN ? AND ? GROUP BY page_code ORDER BY page_views DESC, page_code",
            (rs, row) -> new PageViews(rs.getString("page_code"), rs.getLong("page_views")),
            java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        List<AgentView> agents = jdbc.query("SELECT page_code,user_agent,viewed_at FROM analytics_page_view WHERE visit_date BETWEEN ? AND ? ORDER BY viewed_at DESC LIMIT 100",
            (rs, row) -> new AgentView(rs.getString("page_code"), rs.getString("user_agent"), rs.getTimestamp("viewed_at").toInstant()),
            java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        return new Summary(from, to, daily, pages, agents);
    }

    public static boolean knownPage(String page) { return PAGES.contains(page); }
}
