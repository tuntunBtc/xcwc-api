package com.yanji;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyHash = passwords.encode("not-a-user-password");

    public RegistrationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Session(String token, String username, Instant expiresAt) {}
    public record Bird(String id, String typeId, String name, boolean claimed, Instant claimedAt, String image, String cityCode, String cityName, String typeName, String skinId, String skinName, String batchNo) {}
    public record Provisioned(String id, String claimCode) {}
    private record BirdRow(String id, String typeId, String name, String claimHash, String ownerId, Timestamp claimedAt, String cityCode, String cityName, String typeName, String skinId, String skinName, String batchNo) {}

    public static class Failure extends RuntimeException {
        final HttpStatus status;
        final String code;
        Failure(HttpStatus status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
    }

    private Failure failure(HttpStatus status, String code, String message) { return new Failure(status, code, message); }

    String randomKey(int bytes) {
        byte[] buffer = new byte[bytes]; random.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private void checkPassword(String password) {
        if (password == null || password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密码至少 10 个字符，UTF-8 编码不能超过 72 字节");
        }
    }

    @Transactional
    public Session register(String username, String password) {
        checkPassword(password);
        String id = randomKey(16);
        try { jdbc.update("INSERT INTO app_user(id,username,password_hash,created_at) VALUES(?,?,?,?)", id, username, passwords.encode(password), Timestamp.from(Instant.now())); }
        catch (DuplicateKeyException exception) { throw failure(HttpStatus.CONFLICT, "USERNAME_TAKEN", "该账号已存在，请登录或更换账号"); }
        return issueSession(id, username);
    }

    @Transactional
    public Session login(String username, String password) {
        checkPassword(password);
        var users = jdbc.queryForList("SELECT id,password_hash FROM app_user WHERE username=?", username);
        String hash = users.isEmpty() ? dummyHash : (String) users.getFirst().get("password_hash");
        boolean matches = passwords.matches(password, hash);
        if (users.isEmpty() || !matches) throw failure(HttpStatus.UNAUTHORIZED, "INVALID_LOGIN", "账号或密码不正确");
        return issueSession((String) users.getFirst().get("id"), username);
    }

    private Session issueSession(String id, String username) {
        jdbc.update("DELETE FROM auth_session WHERE expires_at < ?", Timestamp.from(Instant.now()));
        String token = randomKey(32);
        Instant expires = Instant.now().plus(7, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(?,?,?)", digest(token), id, Timestamp.from(expires));
        return new Session(token, username, expires);
    }

    String userId(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [0-9a-f]{64}")) throw failure(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "请先登录");
        var users = jdbc.queryForList("SELECT user_id FROM auth_session WHERE token_hash=? AND expires_at>?", digest(authorization.substring(7)), Timestamp.from(Instant.now()));
        if (users.isEmpty()) throw failure(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "登录已失效，请重新登录");
        return (String) users.getFirst().get("user_id");
    }

    public void logout(String authorization) {
        userId(authorization);
        jdbc.update("DELETE FROM auth_session WHERE token_hash=?", digest(authorization.substring(7)));
    }

    private BirdRow row(java.sql.ResultSet result, int rowNumber) throws java.sql.SQLException {
        return new BirdRow(result.getString("id"), result.getString("type_id"), result.getString("name"), result.getString("claim_hash"), result.getString("owner_id"), result.getTimestamp("claimed_at"), result.getString("city_code"), result.getString("city_name"), result.getString("type_name"), result.getString("skin_id"), result.getString("skin_name"), result.getString("batch_no"));
    }

    private Bird view(BirdRow bird) {
        return new Bird(bird.id(), bird.typeId(), bird.name(), bird.ownerId() != null, bird.claimedAt() == null ? null : bird.claimedAt().toInstant(), "/miniprogram/assets/bird_calm.png", bird.cityCode(), bird.cityName(), bird.typeName(), bird.skinId(), bird.skinName(), bird.batchNo());
    }

    public Bird find(String id) {
        var birds = jdbc.query("SELECT * FROM bird WHERE id=?", this::row, id);
        if (birds.isEmpty()) throw failure(HttpStatus.NOT_FOUND, "BIRD_NOT_FOUND", "没有找到这只小鸟，请检查链接");
        BirdRow bird = birds.getFirst();
        return new Bird(bird.id(), bird.typeId(), "燕叽", bird.ownerId() != null, null, "/miniprogram/assets/bird_calm.png", bird.cityCode(), bird.cityName(), bird.typeName(), bird.skinId(), bird.skinName(), bird.batchNo());
    }

    public List<Bird> mine(String authorization) {
        return jdbc.query("SELECT * FROM bird WHERE owner_id=? ORDER BY claimed_at DESC,id", this::row, userId(authorization)).stream().map(this::view).toList();
    }

    @Transactional
    public Bird claim(String authorization, String id, String code, String name) {
        String user = userId(authorization);
        var birds = jdbc.query("SELECT * FROM bird WHERE id=? FOR UPDATE", this::row, id);
        if (birds.isEmpty()) throw failure(HttpStatus.NOT_FOUND, "BIRD_NOT_FOUND", "没有找到这只小鸟");
        BirdRow bird = birds.getFirst();
        if (!MessageDigest.isEqual(digest(code).getBytes(StandardCharsets.US_ASCII), bird.claimHash().getBytes(StandardCharsets.US_ASCII))) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_CLAIM_CODE", "领养码不正确");
        }
        if (bird.ownerId() != null) {
            if (bird.ownerId().equals(user)) return view(bird);
            throw failure(HttpStatus.CONFLICT, "ALREADY_CLAIMED", "这只小鸟已经被认领");
        }
        Instant now = Instant.now();
        String nickname = name.strip();
        if (nickname.isEmpty()) nickname = "燕叽";
        int changed = jdbc.update("UPDATE bird SET owner_id=?,name=?,claimed_at=? WHERE id=? AND owner_id IS NULL", user, nickname, Timestamp.from(now), id);
        if (changed != 1) throw failure(HttpStatus.CONFLICT, "ALREADY_CLAIMED", "这只小鸟已经被认领");
        return new Bird(id, bird.typeId(), nickname, true, now, "/miniprogram/assets/bird_calm.png", bird.cityCode(), bird.cityName(), bird.typeName(), bird.skinId(), bird.skinName(), bird.batchNo());
    }

    @Transactional
    public void seedDemo() {
        String[][] samples = {
            {"00000000000000000000000000000001", "11111111111111111111111111111111", "hangzhou", "杭州", "demo_white", "测试白色皮肤"},
            {"00000000000000000000000000000002", "22222222222222222222222222222222", "hangzhou", "杭州", "demo_blue", "测试蓝色皮肤"},
            {"00000000000000000000000000000003", "33333333333333333333333333333333", "suzhou", "苏州", "demo_green", "测试绿色皮肤"}
        };
        for (String[] sample : samples) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM bird WHERE id=?", Integer.class, sample[0]) != 0) continue;
            jdbc.update("INSERT INTO bird(id,type_id,name,claim_hash,created_at,city_code,city_name,type_name,skin_id,skin_name,batch_no) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                sample[0], "demo_gaotoubaijie", "测试小鸟", digest(sample[1]), Timestamp.from(Instant.now()), sample[2], sample[3], "高透白洁（测试）", sample[4], sample[5], "DEMO-202610");
        }
    }

    public Provisioned provision() {
        String id = randomKey(16);
        String code = randomKey(16);
        jdbc.update("INSERT INTO bird(id,type_id,name,claim_hash,created_at) VALUES(?,?,?,?,?)", id, "yanji_blue", "燕叽", digest(code), Timestamp.from(Instant.now()));
        return new Provisioned(id, code);
    }
}
