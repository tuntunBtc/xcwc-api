package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class AdoptionTest {
 @Autowired RegistrationService registration;
 @Autowired PointsService points;
 @Autowired JdbcTemplate jdbc;
 @Autowired MockMvc mvc;
 String a,b,bird,second;
 String key(){return UUID.randomUUID().toString();}
 @BeforeEach void setup(){
  for(String table:List.of("checkin_work","checkin_reward_source","checkin_reward_day","bird_binding","points_ledger","user_skin","bird","auth_session","app_user"))jdbc.update("DELETE FROM "+table);
  a="Bearer "+registration.register("adopt_a","password-12345").token();
  b="Bearer "+registration.register("adopt_b","password-12345").token();
  bird=registration.provision().id();second=registration.provision().id();
 }
 @Test void authenticationAndWrongMethodHaveCorrectStatus() throws Exception {
  mvc.perform(post("/bird/v1/birds/"+bird+"/adopt").contentType("application/json").content("{\"requestId\":\"1234567890123456\",\"name\":\"小鸟\"}")).andExpect(status().isUnauthorized());
  mvc.perform(get("/bird/v1/auth/login")).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow",org.hamcrest.Matchers.containsString("POST")));
  mvc.perform(get("/bird/v1/auth/session").header("Authorization",a)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("adopt_a"));
  registration.logout(a);mvc.perform(get("/bird/v1/auth/session").header("Authorization",a)).andExpect(status().isUnauthorized());
 }
 @Test void sameEntityHasIndependentAccountsAndOneWelcomePerAccount(){
  var first=registration.adopt(a,bird,key(),"湖小叽");var other=registration.adopt(b,bird,key(),"黄团子");
  assertThat(first.bound()).isTrue();assertThat(first.eggStatus()).isEqualTo("HATCHED");assertThat(first.hatchedAt()).isNotNull();
  assertThat(first.bindingId()).isNotEqualTo(other.bindingId());
  assertThat(registration.mine(a).getFirst().name()).isEqualTo("湖小叽");assertThat(registration.mine(b).getFirst().name()).isEqualTo("黄团子");
  assertThat(points.wallet(a).balance()).isEqualTo(5);assertThat(points.wallet(b).balance()).isEqualTo(5);
  registration.adopt(a,second,key(),"另一只");assertThat(points.wallet(a).balance()).isEqualTo(5);
  assertThat(points.history(a,Long.MAX_VALUE)).singleElement().satisfies(e->assertThat(e.operation()).isEqualTo("WELCOME"));
 }
 @Test void concurrentRetryCreatesOneBindingOneEggAndOneReward() throws Exception {
  var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var jobs=List.of(1,2).stream().map(i->pool.submit(()->{start.await();return registration.adopt(a,bird,key(),"湖小叽");})).toList();
   start.countDown();assertThat(jobs.get(0).get(15,TimeUnit.SECONDS).bindingId()).isEqualTo(jobs.get(1).get(15,TimeUnit.SECONDS).bindingId());
  }
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bird_binding",Integer.class)).isEqualTo(1);
  assertThat(points.wallet(a).balance()).isEqualTo(5);
 }
 @Test void replayAndUnicodeAndOutfitsStayIsolated(){
  String request=key();var first=registration.adopt(a,bird,request,"🐤🐤🐤🐤🐤🐤");
  assertThat(registration.adopt(a,bird,request,"🐤🐤🐤🐤🐤🐤").bindingId()).isEqualTo(first.bindingId());
  assertThatThrownBy(()->registration.adopt(a,second,request,"其他鸟")).isInstanceOf(RegistrationService.Failure.class);
  assertThatThrownBy(()->registration.adopt(a,second,key(),"🐤🐤🐤🐤🐤🐤🐤")).isInstanceOf(RegistrationService.Failure.class);
  registration.adopt(b,bird,key(),"另一位");
  jdbc.update("INSERT INTO user_skin(user_id,skin_id,unlocked_at) VALUES(?,?,CURRENT_TIMESTAMP)",registration.userId(a),"lake_glow");
  points.equip(a,bird,"lake_glow");assertThat(points.outfits(a).getFirst().skinId()).isEqualTo("lake_glow");assertThat(points.outfits(b).getFirst().skinId()).isNull();
  assertThatThrownBy(()->points.equip(b,bird,"lake_glow")).isInstanceOf(RegistrationService.Failure.class);
  assertThat(jdbc.queryForObject("SELECT equipped_skin_id FROM bird WHERE id=?",String.class,bird)).isNull();
 }
}
