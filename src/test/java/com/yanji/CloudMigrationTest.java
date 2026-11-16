package com.yanji;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CloudMigrationTest {
    @Test void upgradeFromV10PreservesIdentityAssetsAndLiveSessionWithoutInventingState(){
        var ds=new DriverManagerDataSource(
            System.getenv().getOrDefault("TEST_CLOUD_MIGRATION_DB_URL","jdbc:h2:mem:cloud_upgrade_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1"),
            System.getenv().getOrDefault("TEST_CLOUD_MIGRATION_DB_USERNAME","sa"),
            System.getenv().getOrDefault("TEST_CLOUD_MIGRATION_DB_PASSWORD",""));
        Flyway.configure().dataSource(ds).target("10").load().migrate();
        var db=new JdbcTemplate(ds);String user="a".repeat(32),bird="b".repeat(32),pet="c".repeat(32),token="d".repeat(64);
        byte[] openid="Legacy-CaseSensitive-Fixture".getBytes(StandardCharsets.UTF_8);
        db.update("INSERT INTO app_user(id,username,password_hash,created_at,wx_openid,points_balance,initial_reward_received) VALUES(?,?,?,CURRENT_TIMESTAMP,?,?,TRUE)",user,"legacy_fixture","fixture",openid,37);
        db.update("INSERT INTO bird(id,type_id,name,claim_hash,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",bird,"fixture","Bird","e".repeat(64));
        db.update("INSERT INTO bird_binding(id,user_id,bird_id,nickname,adoption_name,created_at,hatched_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",pet,user,bird,"Old","Old");
        db.update("INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(?,?,?)",token,user,Timestamp.from(Instant.now().plusSeconds(86400)));
        db.update("INSERT INTO wx_session(token_hash,session_key) VALUES(?,?)",token,"fixture-session-key");
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(db.queryForObject("SELECT wx_openid FROM app_user WHERE id=?",byte[].class,user)).isEqualTo(openid);
        assertThat(db.queryForObject("SELECT wx_appid FROM app_user WHERE id=?",byte[].class,user)).isNull();
        assertThat(db.queryForObject("SELECT points_balance FROM app_user WHERE id=?",Long.class,user)).isEqualTo(37);
        assertThat(db.queryForObject("SELECT nickname FROM bird_binding WHERE id=?",String.class,pet)).isEqualTo("Old");
        assertThat(db.queryForObject("SELECT session_key FROM wx_session WHERE token_hash=?",String.class,token)).isEqualTo("fixture-session-key");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM pet_state",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM pet_operation",Integer.class)).isZero();
    }
}
