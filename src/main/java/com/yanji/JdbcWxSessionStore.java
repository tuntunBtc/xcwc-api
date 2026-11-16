package com.yanji;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWxSessionStore implements WxSessionStore {
    private final JdbcTemplate jdbc;
    public JdbcWxSessionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public String sessionKey(String token) {
        var rows = jdbc.queryForList("SELECT session_key FROM wx_session WHERE token_hash=?",
            String.class, RegistrationService.digest(token));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public void save(String token, String sessionKey) {
        jdbc.update("INSERT INTO wx_session(token_hash,session_key) VALUES(?,?)",
            RegistrationService.digest(token), sessionKey);
    }
}
