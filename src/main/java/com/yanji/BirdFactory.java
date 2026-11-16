package com.yanji;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Offline manufacture entry; never exposed as a public HTTP creation endpoint. */
@Service
public class BirdFactory {
    public record Spec(String city, String ip, String skinId, String skinName, String image, String batch) {}
    public record Manufactured(String id, String birdCode, String linkSuffix) {}
    private final JdbcTemplate jdbc;
    private final RegistrationService registration;
    private final BirdSnowflake snowflake;
    private final TransactionTemplate storage;
    public BirdFactory(JdbcTemplate jdbc, RegistrationService registration, BirdSnowflake snowflake, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.registration=registration; this.snowflake=snowflake; this.storage=new TransactionTemplate(manager);
    }
    public void validate(Spec spec) {
        if (spec == null || spec.city() == null || !spec.city().matches("[A-Z]{2}") ||
            spec.ip() == null || !spec.ip().matches("[A-Z][A-Z0-9]{1,7}") ||
            spec.skinId() == null || !spec.skinId().matches("[a-z0-9_]{1,32}") ||
            spec.skinName() == null || spec.skinName().isBlank() || spec.skinName().length() > 48 ||
            spec.batch() == null || !spec.batch().matches("[A-Za-z0-9_-]{1,48}") ||
            spec.image() == null || spec.image().length() > 256 ||
            !spec.image().matches("/(?:assets|miniprogram/assets)/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.(?:png|jpg|jpeg|webp|avif)"))
            throw new RegistrationService.Failure(HttpStatus.BAD_REQUEST,"INVALID_MANUFACTURING_SPEC","请提供有效城市、IP、出厂皮肤、静态图片路径和批次；不接受空值或未知资料");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM bird_city_code WHERE code=?",Integer.class,spec.city()) != 1)
            throw new RegistrationService.Failure(HttpStatus.BAD_REQUEST,"UNKNOWN_CITY_CODE","城市编码尚未登记");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM bird_ip_code WHERE code=? AND enabled=TRUE",Integer.class,spec.ip()) != 1)
            throw new RegistrationService.Failure(HttpStatus.BAD_REQUEST,"IP_CODE_NOT_ENABLED","IP 编码未登记或尚未确认启用");
    }
    public Manufactured create(Spec spec) {
        validate(spec);
        // Reserve before opening the insert transaction: batch callers do not consume two pool slots.
        String code=BirdCodes.validate(spec.city()+"-"+spec.ip()+"-"+snowflake.nextId());
        return storage.execute(status -> {
        validate(spec);
        var city=jdbc.queryForMap("SELECT city_key,name FROM bird_city_code WHERE code=?",spec.city());
        String typeName=jdbc.queryForObject("SELECT name FROM bird_ip_code WHERE code=? AND enabled=TRUE",String.class,spec.ip());
        String id=registration.randomKey(16);
        // Compatibility-only hash: no hidden credential is exported or used for new NFC binding.
        String hash=RegistrationService.digest(registration.randomKey(32));
        jdbc.update("INSERT INTO bird(id,type_id,name,claim_hash,created_at,city_code,city_name,type_name,skin_id,skin_name,batch_no,bird_code,ip_code,code_city,image_path) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, "ip_"+spec.ip().toLowerCase(java.util.Locale.ROOT), typeName, hash, Timestamp.from(Instant.now()),
            city.get("city_key"),city.get("name"),typeName,spec.skinId(),spec.skinName(),spec.batch(),code,spec.ip(),spec.city(),spec.image());
        return new Manufactured(id,code,"/register.html?birdCode="+code);
        });
    }
}
