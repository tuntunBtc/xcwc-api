package com.yanji;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
class AdminMigrationTest {
    @Test void v14ToBrandIpKeepsExistingContentAndRoleGrants(){
        var ds=new DriverManagerDataSource(System.getenv().getOrDefault("TEST_BRAND_MIGRATION_DB_URL","jdbc:h2:mem:brand_migration_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1"),System.getenv().getOrDefault("TEST_BRAND_MIGRATION_DB_USERNAME","sa"),System.getenv().getOrDefault("TEST_BRAND_MIGRATION_DB_PASSWORD",""));
        Flyway.configure().dataSource(ds).target("14").load().migrate();var db=new JdbcTemplate(ds);
        String payload=db.queryForObject("SELECT draft FROM content_workspace WHERE id=1",String.class);
        db.update("INSERT INTO content_release(id,workspace_version,payload,actor_id,created_at) VALUES(?,7,?,1,CURRENT_TIMESTAMP)","a".repeat(32),payload);
        db.update("UPDATE content_workspace SET version=8,published_id=? WHERE id=1","a".repeat(32));
        var beforeWorkspace=db.queryForList("SELECT * FROM content_workspace");var beforeReleases=db.queryForList("SELECT * FROM content_release");var grants=db.queryForList("SELECT * FROM sys_role_menu");
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(db.queryForList("SELECT * FROM content_workspace")).isEqualTo(beforeWorkspace);
        assertThat(db.queryForList("SELECT * FROM content_release")).isEqualTo(beforeReleases);
        assertThat(db.queryForList("SELECT * FROM sys_role_menu")).containsAll(grants);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM brand_ip_revision",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE perms LIKE 'brand:%'",Integer.class)).isEqualTo(7);
    }
    @Test void v12ToAdminPreservesExistingPetProgressInventoryAndUserWallet(){
        var ds=new DriverManagerDataSource(System.getenv().getOrDefault("TEST_ADMIN_MIGRATION_DB_URL","jdbc:h2:mem:admin_migration_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1"),System.getenv().getOrDefault("TEST_ADMIN_MIGRATION_DB_USERNAME","sa"),System.getenv().getOrDefault("TEST_ADMIN_MIGRATION_DB_PASSWORD",""));
        Flyway.configure().dataSource(ds).target("12").load().migrate();var db=new JdbcTemplate(ds);
        String u="f".repeat(32),b="e".repeat(32),p="d".repeat(32),token="c".repeat(64);
        db.update("INSERT INTO app_user(id,username,password_hash,created_at,points_balance,cloud_pet_enabled) VALUES(?,?,?,CURRENT_TIMESTAMP,73,TRUE)",u,"migration_fixture","fixture");
        db.update("INSERT INTO bird(id,type_id,name,claim_hash,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",b,"fixture","Bird","b".repeat(64));
        db.update("INSERT INTO bird_binding(id,user_id,bird_id,nickname,adoption_name,created_at,hatched_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",p,u,b,"Old","Old");
        db.update("INSERT INTO pet_state(binding_id,version,satiety,hydration,mood,food,water,body_at,mood_at,companion_at,poop_due_at) VALUES(?,17,21,32,43,54,65,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",p);
        db.update("INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(?,?,TIMESTAMP '2030-01-01 00:00:00')",token,u);
        db.update("INSERT INTO user_skin(user_id,skin_id,unlocked_at) VALUES(?,'lake_glow',CURRENT_TIMESTAMP)",u);
        var tables=List.of("app_user","bird","bird_binding","pet_state","auth_session","user_skin");var before=new LinkedHashMap<String,Object>();for(String table:tables)before.put(table,db.queryForList("SELECT * FROM "+table));
        Flyway.configure().dataSource(ds).load().migrate();for(String table:tables)assertThat(db.queryForList("SELECT * FROM "+table)).as(table).isEqualTo(before.get(table));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user",Integer.class)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM wardrobe_entitlement",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM pet_spark",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM pet_spark_day",Integer.class)).isZero();
    }
}
