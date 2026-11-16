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

    private final org.springframework.beans.factory.ObjectProvider<PointsService> points;
    public RegistrationService(JdbcTemplate jdbc, org.springframework.beans.factory.ObjectProvider<PointsService> points) { this.jdbc = jdbc; this.points=points; }

    public record Session(String token, String username, Instant expiresAt) {}
    public record Bird(String id, String typeId, String name, boolean claimed, Instant claimedAt, String image, String cityCode, String cityName, String typeName, String skinId, String skinName, String batchNo, String birdCode, String ipCode, String codeCity, String imagePath, boolean active) {}
    public record Provisioned(String id, String claimCode) {}
    private record BirdRow(String id, String typeId, String name, String claimHash, String ownerId, Timestamp claimedAt, String cityCode, String cityName, String typeName, String skinId, String skinName, String batchNo, String birdCode, String ipCode, String codeCity, String imagePath, boolean active) {}

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
        var users = jdbc.queryForList("SELECT id,password_hash FROM app_user WHERE username=? AND wx_openid IS NULL", username);
        String hash = users.isEmpty() ? dummyHash : (String) users.getFirst().get("password_hash");
        boolean matches = passwords.matches(password, hash);
        if (users.isEmpty() || !matches) throw failure(HttpStatus.UNAUTHORIZED, "INVALID_LOGIN", "账号或密码不正确");
        return issueSession((String) users.getFirst().get("id"), username);
    }

    private Session issueSession(String id, String username) {
        jdbc.update("DELETE FROM auth_session WHERE expires_at < ?", Timestamp.from(Instant.now()));
        String token = randomKey(32);
        // Match the persisted TIMESTAMP precision so login and session reads agree.
        Instant expires = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(?,?,?)", digest(token), id, Timestamp.from(expires));
        return new Session(token, username, expires);
    }

    @org.springframework.beans.factory.annotation.Value("${app.wx.app-id:}")
    private String wxAppId;
    @org.springframework.beans.factory.annotation.Value("${app.wx.legacy-app-id:}")
    private String legacyAppId;

    public String ensureUserByOpenid(String openid) {
        return ensureWechatUser(wxAppId, openid);
    }

    String ensureWechatUser(String appId, String openid) {
        if (appId == null || !appId.matches("[A-Za-z0-9_-]{1,64}") || openid == null || openid.isBlank()
                || openid.codePointCount(0, openid.length()) > 64
                || !StandardCharsets.UTF_8.newEncoder().canEncode(openid)) {
            throw failure(HttpStatus.UNAUTHORIZED, "WX_LOGIN_FAILED", "Invalid WeChat identity");
        }
        byte[] app = appId.getBytes(StandardCharsets.UTF_8);
        byte[] identity = openid.getBytes(StandardCharsets.UTF_8);
        var rows = jdbc.queryForList("SELECT id FROM app_user WHERE wx_appid=? AND wx_openid=?", app, identity);
        if (!rows.isEmpty()) return (String) rows.getFirst().get("id");
        var legacy = jdbc.queryForList("SELECT id FROM app_user WHERE wx_appid IS NULL AND wx_openid=? FOR UPDATE", identity);
        if (!legacy.isEmpty()) {
            if (!appId.equals(legacyAppId) || legacy.size() != 1)
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "WX_IDENTITY_MIGRATION_REQUIRED", "Legacy WeChat AppID must be verified");
            String id = (String) legacy.getFirst().get("id");
            jdbc.update("UPDATE app_user SET wx_appid=? WHERE id=? AND wx_appid IS NULL", app, id);
            return id;
        }
        String id = randomKey(16);
        String username = "wx_" + digest(appId + ":" + openid).substring(0, 29);
        try {
            jdbc.update("INSERT INTO app_user(id,username,password_hash,wx_appid,wx_openid,created_at) VALUES(?,?,?,?,?,?)",
                id, username, dummyHash, app, identity, Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException error) {
            rows = jdbc.queryForList("SELECT id FROM app_user WHERE wx_appid=? AND wx_openid=?", app, identity);
            if (!rows.isEmpty()) return (String) rows.getFirst().get("id");
            throw error;
        }
        return id;
    }

    /** 为已存在的用户下发登录令牌（微信登录复用账号体系）。 */
    public Session issueSessionForUser(String userId) {
        String username = jdbc.queryForObject("SELECT username FROM app_user WHERE id=?", String.class, userId);
        return issueSession(userId, username);
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
        return new BirdRow(result.getString("id"), result.getString("type_id"), result.getString("name"), result.getString("claim_hash"), result.getString("owner_id"), result.getTimestamp("claimed_at"), result.getString("city_code"), result.getString("city_name"), result.getString("type_name"), result.getString("skin_id"), result.getString("skin_name"), result.getString("batch_no"), result.getString("bird_code"), result.getString("ip_code"), result.getString("code_city"), result.getString("image_path"), result.getBoolean("active"));
    }

    private Bird view(BirdRow bird) {
        return new Bird(bird.id(), bird.typeId(), bird.name(), bird.ownerId() != null, bird.claimedAt() == null ? null : bird.claimedAt().toInstant(), bird.imagePath(), bird.cityCode(), bird.cityName(), bird.typeName(), bird.skinId(), bird.skinName(), bird.batchNo(), bird.birdCode(), bird.ipCode(), bird.codeCity(), bird.imagePath(), bird.active());
    }

    public Bird find(String id) {
        var birds = jdbc.query("SELECT * FROM bird WHERE id=?", this::row, id);
        if (birds.isEmpty()) throw failure(HttpStatus.NOT_FOUND, "BIRD_NOT_FOUND", "没有找到这只小鸟，请检查链接");
        return publicBird(birds.getFirst());
    }

    private Bird publicBird(BirdRow bird) {
        requireActive(bird);
        return new Bird(bird.id(), bird.typeId(), bird.birdCode() == null ? "燕叽" : bird.typeName(), false, null, bird.imagePath(), bird.cityCode(), bird.cityName(), bird.typeName(), bird.skinId(), bird.skinName(), bird.batchNo(), bird.birdCode(), bird.ipCode(), bird.codeCity(), bird.imagePath(), bird.active());
    }

    private void requireActive(BirdRow bird) {
        if (!bird.active()) throw failure(HttpStatus.GONE, "BIRD_INACTIVE", "这只实体小鸟已停用，请联系工作人员");
    }

    public Bird findByCode(String code) {
        BirdCodes.validate(code);
        var birds = jdbc.query("SELECT * FROM bird WHERE bird_code=?", this::row, code);
        if (birds.isEmpty()) throw failure(HttpStatus.NOT_FOUND, "BIRD_NOT_FOUND", "没有找到这只小鸟，请检查完整编码");
        return publicBird(birds.getFirst());
    }

    private Bird bindingBird(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        BirdRow b=row(r,n);
        return new Bird(b.id(),b.typeId(),r.getString("binding_name"),true,r.getTimestamp("binding_at").toInstant(),b.imagePath(),
            b.cityCode(),b.cityName(),b.typeName(),b.skinId(),b.skinName(),b.batchNo(),b.birdCode(),b.ipCode(),b.codeCity(),b.imagePath(),b.active());
    }
    public List<Bird> mine(String authorization) {
        return jdbc.query("SELECT b.*,bb.nickname AS binding_name,bb.created_at AS binding_at FROM bird_binding bb JOIN bird b ON b.id=bb.bird_id WHERE bb.user_id=? ORDER BY bb.created_at DESC,b.id",this::bindingBird,userId(authorization));
    }
    public record Binding(String birdId, boolean bound, String bindingId, String nickname, String eggId, String eggStatus, Instant hatchedAt) {}
    public Binding binding(String auth,String birdId) { return bindingFor(userId(auth),birdId); }
    private Binding bindingFor(String user,String birdId) {
        var rows=jdbc.query("SELECT * FROM bird_binding WHERE user_id=? AND bird_id=? FOR UPDATE",(r,n)->
            new Binding(birdId,true,r.getString("id"),r.getString("nickname"),r.getString("id"),"HATCHED",r.getTimestamp("hatched_at").toInstant()),user,birdId);
        return rows.isEmpty()?new Binding(birdId,false,null,null,null,null,null):rows.getFirst();
    }
    public record CurrentSession(String userId,String username,Instant expiresAt) {}
    public CurrentSession session(String auth) {
        String user=userId(auth);
        return jdbc.queryForObject("SELECT u.username,s.expires_at FROM auth_session s JOIN app_user u ON u.id=s.user_id WHERE s.token_hash=?",
            (r,n)->new CurrentSession(user,r.getString(1),r.getTimestamp(2).toInstant()),digest(auth.substring(7)));
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Binding adopt(String auth,String birdId,String requestId,String name) {
        String user=userId(auth);
        jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",String.class,user);
        if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{16,64}"))throw failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_ID","请提供有效请求编号");
        if(name==null)throw failure(HttpStatus.BAD_REQUEST,"INVALID_NAME","请输入 1～6 个字的昵称");
        String nickname=name.strip();int length=nickname.codePointCount(0,nickname.length());
        if(length<1||length>6||nickname.codePoints().anyMatch(Character::isISOControl))throw failure(HttpStatus.BAD_REQUEST,"INVALID_NAME","请输入 1～6 个字的昵称");
        var prior=jdbc.queryForList("SELECT bird_id,adoption_name FROM bird_binding WHERE user_id=? AND request_id=?",user,requestId);
        if(!prior.isEmpty()&&(!birdId.equals(prior.getFirst().get("bird_id"))||!nickname.equals(prior.getFirst().get("adoption_name"))))
            throw failure(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","同一请求编号不能用于不同领养内容");
        var birds=jdbc.query("SELECT * FROM bird WHERE id=? FOR UPDATE",this::row,birdId);
        if(birds.isEmpty())throw failure(HttpStatus.NOT_FOUND,"BIRD_NOT_FOUND","没有找到这只小鸟");
        requireActive(birds.getFirst());
        var existing=jdbc.queryForList("SELECT id FROM bird_binding WHERE user_id=? AND bird_id=? FOR UPDATE",user,birdId);
        if(!existing.isEmpty())return bindingFor(user,birdId);
        var publications=jdbc.queryForList("SELECT r.payload FROM content_workspace w JOIN content_release r ON r.id=w.published_id WHERE w.id=1",String.class);
        var roles=jdbc.queryForList("SELECT character_id FROM content_entity WHERE bird_id=?",String.class,birdId);
        if(!roles.isEmpty()&&!publications.isEmpty()){
            try{var config=new com.fasterxml.jackson.databind.ObjectMapper().readTree(publications.getFirst());boolean enabled=false;for(var role:config.path("characters"))if(role.path("id").asText().equals(roles.getFirst()))enabled=role.path("adoptionEnabled").asBoolean();
                if(!enabled)throw failure(HttpStatus.CONFLICT,"CHARACTER_ADOPTION_CLOSED","该角色暂不开放新领养");
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        }
        Instant now=Instant.now();String id=randomKey(16);
        jdbc.update("INSERT INTO bird_binding(id,user_id,bird_id,nickname,adoption_name,request_id,created_at,hatched_at) VALUES(?,?,?,?,?,?,?,?)",
            id,user,birdId,nickname,nickname,requestId,Timestamp.from(now),Timestamp.from(now));
        if(!jdbc.queryForObject("SELECT initial_reward_received FROM app_user WHERE id=? FOR UPDATE",Boolean.class,user)) {
            points.getObject().welcome(user,id);
            jdbc.update("UPDATE app_user SET initial_reward_received=TRUE WHERE id=?",user);
        }
        return bindingFor(user,birdId);
    }
    /** Compatibility adapter. Hidden codes are no longer required or used. */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Bird claim(String auth,String id,String ignoredCode,String name) {
        adopt(auth,id,"legacy_adopt_"+id,name);
        return jdbc.queryForObject("SELECT b.*,bb.nickname AS binding_name,bb.created_at AS binding_at FROM bird_binding bb JOIN bird b ON b.id=bb.bird_id WHERE bb.user_id=? AND bb.bird_id=? FOR UPDATE",this::bindingBird,userId(auth),id);
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
