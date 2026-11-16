package com.yanji;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
class BindingMigrationTest {
 @Test void v5OwnerKeepsNicknameOutfitAndBalanceDuringV6(){
  var ds=new DriverManagerDataSource(
   System.getenv().getOrDefault("TEST_MIGRATION_DB_URL","jdbc:h2:mem:binding_migration;MODE=MySQL;DB_CLOSE_DELAY=-1"),
   System.getenv().getOrDefault("TEST_MIGRATION_DB_USERNAME","sa"),
   System.getenv().getOrDefault("TEST_MIGRATION_DB_PASSWORD",""));
  Flyway.configure().dataSource(ds).target("5").load().migrate();var jdbc=new JdbcTemplate(ds);
  jdbc.update("INSERT INTO app_user(id,username,password_hash,created_at,points_balance) VALUES('olduser','old_user','hash',CURRENT_TIMESTAMP,37)");
  jdbc.update("INSERT INTO bird(id,type_id,name,claim_hash,owner_id,created_at,equipped_skin_id) VALUES('oldbird','old_type','旧昵称','hash','olduser',CURRENT_TIMESTAMP,'lake_glow')");
  Flyway.configure().dataSource(ds).load().migrate();
  assertThat(jdbc.queryForObject("SELECT nickname FROM bird_binding",String.class)).isEqualTo("旧昵称");
  assertThat(jdbc.queryForObject("SELECT equipped_skin_id FROM bird_binding",String.class)).isEqualTo("lake_glow");
  assertThat(jdbc.queryForObject("SELECT points_balance FROM app_user",Long.class)).isEqualTo(37);
  assertThat(jdbc.queryForObject("SELECT initial_reward_received FROM app_user",Boolean.class)).isTrue();
 }
}
