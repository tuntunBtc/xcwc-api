package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class CloudPetTest {
    @Autowired CloudPetService pets;
    @Autowired RegistrationService users;
    @Autowired PointsService points;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    Instant time;String auth,user,bird,pet;
    String key(){return UUID.randomUUID().toString();}
    @BeforeEach void setup(){
        time=Instant.parse("2026-11-11T15:59:00Z");when(clock.instant()).thenAnswer(i->time);
        var session=users.register("c_"+users.randomKey(8),"password-fixture-123");auth="Bearer "+session.token();user=users.userId(auth);
        bird=users.provision().id();pet=pet(pets.adopt(auth,new CloudPetService.Adoption(key(),bird,"Bird")));
    }
    @SuppressWarnings("unchecked") Map<String,Object> pv(Map<String,Object> r){return (Map<String,Object>)r.get("pet");}
    String pet(Map<String,Object> r){return (String)pv(r).get("petId");}
    String version(){return (String)pv(pets.detail(auth,pet)).get("version");}
    CloudPetService.Command cmd(String id,String version,String action){return new CloudPetService.Command(id,version,action,null,action.equals("CLAIM_SUPPLY")?time.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString():null);}
    Map<String,Object> act(String action){return pets.act(auth,pet,cmd(key(),version(),action));}
    @SuppressWarnings("unchecked") int stat(Map<String,Object> r,String group,String name){return ((Number)((Map<String,Object>)pv(r).get(group)).get(name)).intValue();}
    @Test void restoresSharedIdentityIndependentPetsAndInitialInventoryOnce(){
        String secondAuth="Bearer "+users.issueSessionForUser(user).token();
        assertThat(pets.detail(secondAuth,pet)).isEqualTo(pets.detail(auth,pet));
        assertThat(stat(pets.detail(auth,pet),"inventory","food")).isEqualTo(3);
        var again=pets.adopt(auth,new CloudPetService.Adoption(key(),bird,"Other"));
        assertThat(pet(again)).isEqualTo(pet);assertThat(pv(again).get("nickname")).isEqualTo("Bird");
        assertThat(pets.inventoryHistory(auth,pet,Long.MAX_VALUE)).hasSize(1);
        String other="Bearer "+users.register("c_"+users.randomKey(8),"password-fixture-123").token();
        var otherPet=pets.adopt(other,new CloudPetService.Adoption(key(),bird,"Other"));assertThat(pet(otherPet)).isNotEqualTo(pet);
        assertThatThrownBy(()->pets.detail(other,pet)).isInstanceOf(RegistrationService.Failure.class);
        assertThat(points.wallet(auth).balance()).isEqualTo(5);
    }
    @Test void atomicFeedReplayConflictAndPendingEventSurviveRead(){
        String id=key();var command=cmd(id,version(),"FEED");var first=pets.act(auth,pet,command);
        assertThat(stat(first,"inventory","food")).isEqualTo(2);assertThat(stat(first,"care","satiety")).isEqualTo(90);
        assertThat(pets.act(auth,pet,command).get("replayed")).isEqualTo(true);
        assertThat(pets.operation(auth,id).get("replayed")).isEqualTo(true);
        assertThatThrownBy(()->pets.act(auth,pet,cmd(id,command.expectedVersion(),"WATER"))).isInstanceOf(RegistrationService.Failure.class);
        var early=act("CLEAN");assertThat(pv(early).get("cleaning").toString()).contains("PENDING");
        time=time.plusSeconds(60);assertThat(pv(pets.detail(auth,pet)).get("cleaning").toString()).contains("PRESENT");
        assertThat(pv(act("CLEAN")).get("cleaning").toString()).contains("NONE");
        assertThat(pets.inventoryHistory(auth,pet,Long.MAX_VALUE)).hasSize(2);
    }
    @Test void lastFoodConcurrentRequestsConsumeExactlyOnce() throws Exception {
        jdbc.update("UPDATE pet_state SET food=1 WHERE binding_id=?",pet);String v=version();
        var barrier=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{barrier.await();try{pets.act(auth,pet,cmd(key(),v,"FEED"));return true;}catch(RegistrationService.Failure e){assertThat(e.code).isEqualTo("PET_VERSION_CONFLICT");return false;}}));
            barrier.countDown();int successes=0;for(var f:futures)if(f.get(15,TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);
        }finally{pool.shutdownNow();}
        assertThat(stat(pets.detail(auth,pet),"inventory","food")).isZero();
    }
    @Test void accountDailySupplyCannotBeRepeatedAcrossBirdsOrDayRetry(){
        String second=pet(pets.adopt(auth,new CloudPetService.Adoption(key(),users.provision().id(),"Two")));
        String id=key();var command=cmd(id,version(),"CLAIM_SUPPLY");var first=pets.act(auth,pet,command);
        assertThat(stat(first,"inventory","food")).isEqualTo(4);
        var otherVersion=(String)pv(pets.detail(auth,second)).get("version");
        assertThat(pets.act(auth,second,cmd(key(),otherVersion,"CLAIM_SUPPLY")).get("outcome")).isEqualTo("ALREADY_CLAIMED");
        time=time.plusSeconds(61);
        assertThat(pets.act(auth,pet,command).get("replayed")).isEqualTo(true);
        assertThat(stat(act("CLAIM_SUPPLY"),"inventory","food")).isEqualTo(5);
        assertThat(pets.claims(auth,LocalDate.of(9999,12,31))).hasSize(2);
    }
    @Test void petLimitDoesNotBlockCompanionship(){
        time=Instant.parse("2026-11-11T01:00:00Z");
        jdbc.update("UPDATE app_user SET pet_reward_date=?,pet_reward_count=10 WHERE id=?",LocalDate.of(2026,11,11),user);
        var result=act("PET");assertThat(result.get("reward")).isEqualTo(0);
        assertThat(stat(result,"care","mood")).isEqualTo(100);
        assertThat(points.wallet(auth).balance()).isEqualTo(5);
        assertThatThrownBy(()->points.awardSteps(user,key(),"2026-11-11",5)).isInstanceOf(RegistrationService.Failure.class);
    }
    @Test void requestRollsBackOnFailureAndRejectsAssetInjection() throws Exception {
        jdbc.update("UPDATE pet_state SET food=0 WHERE binding_id=?",pet);String v=version();String id=key();
        assertThatThrownBy(()->pets.act(auth,pet,cmd(id,v,"FEED"))).isInstanceOf(RegistrationService.Failure.class);
        assertThat(version()).isEqualTo(v);
        assertThatThrownBy(()->pets.operation(auth,id)).isInstanceOf(RegistrationService.Failure.class);
        mvc.perform(post("/api/me/pets/"+pet+"/actions").header("Authorization",auth).contentType("application/json")
            .content("{\"requestId\":\""+key()+"\",\"expectedVersion\":\""+v+"\",\"action\":\"PET\",\"points\":999}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/pets")).andExpect(status().isUnauthorized());
    }
    @Test void appIdsHaveIndependentExactIdentities(){
        String openid="fixture_"+users.randomKey(8);
        String a=users.ensureWechatUser("app_a",openid);String b=users.ensureWechatUser("app_b",openid);
        assertThat(a).isNotEqualTo(b);assertThat(users.ensureWechatUser("app_a",openid)).isEqualTo(a);
        assertThat(users.ensureWechatUser("app_a",openid.toUpperCase(Locale.ROOT))).isNotEqualTo(a);
    }
    @Test void concurrentSameRequestReturnsOneReceiptAndOneLedger() throws Exception {
        String id=key();var command=cmd(id,version(),"FEED");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return pets.act(auth,pet,command);});
            var b=pool.submit(()->{start.await();return pets.act(auth,pet,command);});start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS).get("replayed"),b.get(15,TimeUnit.SECONDS).get("replayed"))).containsExactlyInAnyOrder(false,true);
        }
        assertThat(stat(pets.detail(auth,pet),"inventory","food")).isEqualTo(2);
        assertThat(pets.inventoryHistory(auth,pet,Long.MAX_VALUE)).hasSize(2);
    }
    @Test void expiredLegacyReceiptConsumesEligibilityWithoutImportingInventory(){
        jdbc.update("INSERT INTO wx_supply_claim(user_id,claim_date,receipt_id,installation_hash,bird_code) VALUES(?,?,?,?,?)",
            user,time.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),users.randomKey(16),RegistrationService.digest("old-install"),"legacy-fixture");
        assertThat(act("CLAIM_SUPPLY").get("outcome")).isEqualTo("ALREADY_CLAIMED");
        assertThat(stat(pets.detail(auth,pet),"inventory","food")).isEqualTo(3);
        assertThat(pets.claims(auth,LocalDate.of(9999,12,31))).isEmpty();
    }
    @Test void walletFailureRollsBackWholePetOperation(){
        jdbc.update("UPDATE app_user SET points_balance=? WHERE id=?",Long.MAX_VALUE,user);
        String id=key(),v=version();
        assertThatThrownBy(()->pets.act(auth,pet,cmd(id,v,"PET"))).isInstanceOf(ArithmeticException.class);
        assertThat(version()).isEqualTo(v);
        assertThat(stat(pets.detail(auth,pet),"care","mood")).isEqualTo(80);
        assertThatThrownBy(()->pets.operation(auth,id)).isInstanceOf(RegistrationService.Failure.class);
    }
    @Test void legacyWechatIdentityRequiresExplicitVerifiedAppId(){
        String openid="legacy-fixture-"+users.randomKey(8);
        jdbc.update("UPDATE app_user SET wx_openid=?,wx_appid=NULL WHERE id=?",openid.getBytes(java.nio.charset.StandardCharsets.UTF_8),user);
        assertThatThrownBy(()->users.ensureWechatUser("legacy_app",openid)).isInstanceOf(RegistrationService.Failure.class);
        Object previous=org.springframework.test.util.ReflectionTestUtils.getField(users,"legacyAppId");
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(users,"legacyAppId","legacy_app");
            assertThat(users.ensureWechatUser("legacy_app",openid)).isEqualTo(user);
            assertThat(users.ensureWechatUser("another_app",openid)).isNotEqualTo(user);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(users,"legacyAppId",previous);}
    }

    @Test void concurrentFirstWechatLoginCreatesOnlyOneScopedIdentity() throws Exception {
        String openid="concurrent-fixture-"+users.randomKey(8);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return users.ensureWechatUser("same_app",openid);});
            var b=pool.submit(()->{start.await();return users.ensureWechatUser("same_app",openid);});start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
        }
    }
    @Test void renameAndFullFeedingDoNotRefreshCompanionship(){
        Instant before=time;time=time.plusSeconds(10);
        var renamed=pets.act(auth,pet,new CloudPetService.Command(key(),version(),"RENAME","New",null));
        assertThat(pv(renamed).get("nickname")).isEqualTo("New");
        assertThat(pv(renamed).get("care").toString()).contains(before.toString());
        act("FEED");act("FEED");time=time.plusSeconds(10);
        var full=act("FEED");assertThat(full.get("outcome")).isEqualTo("ALREADY_FULL");
        assertThat(stat(full,"inventory","food")).isEqualTo(1);
        assertThat(pets.inventoryHistory(auth,pet,Long.MAX_VALUE)).hasSize(3);
        var spacedId="CaseSensitiveRequest_A";act("WATER");
        pets.act(auth,pet,cmd(spacedId,version(),"WATER"));
        // Case-distinct commands must not collide under a case-insensitive MySQL collation.
        jdbc.update("UPDATE pet_state SET hydration=60 WHERE binding_id=?",pet);
        pets.act(auth,pet,cmd("CaseSensitiveRequest_a",version(),"WATER"));
    }

    @Test void getAndHeadNeverInitializeAndPostIsConvergent() throws Exception {
        String token="Bearer "+users.register("safe_"+users.randomKey(8),"password-fixture-123").token();
        String uid=users.userId(token), oldBird=users.provision().id();
        String oldPet=users.adopt(token,oldBird,key(),"Old").bindingId();
        for(int i=0;i<2;i++) {
            mvc.perform(get("/api/me/pets").header("Authorization",token)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOUD_INITIALIZATION_REQUIRED"));
            mvc.perform(head("/api/me/pets/"+oldPet).header("Authorization",token)).andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject("SELECT cloud_pet_enabled FROM app_user WHERE id=?",Boolean.class,uid)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pet_state WHERE binding_id=?",Integer.class,oldPet)).isZero();
        long balance=points.wallet(token).balance();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return pets.initializeAccount(token);});
            var b=pool.submit(()->{start.await();return pets.initializeAccount(token);});start.countDown();
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        }
        var before=jdbc.queryForMap("SELECT * FROM pet_state WHERE binding_id=?",oldPet);
        time=time.plusSeconds(7200);
        mvc.perform(get("/api/me/pets").header("Authorization",token)).andExpect(status().isOk());
        mvc.perform(head("/api/me/pets/"+oldPet).header("Authorization",token)).andExpect(status().isOk());
        pets.initializeAccount(token);
        assertThat(jdbc.queryForMap("SELECT * FROM pet_state WHERE binding_id=?",oldPet)).isEqualTo(before);
        assertThat(pets.inventoryHistory(token,oldPet,Long.MAX_VALUE)).hasSize(1);
        assertThat(points.wallet(token).balance()).isEqualTo(balance);
    }
    @Test void httpCreationAndAuthenticationSemantics() throws Exception {
        mvc.perform(get("/api/me/pets")).andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate","Bearer realm=\"xcwc\""));
        String id=key(),newBird=users.provision().id();
        String body="{\"requestId\":\""+id+"\",\"birdId\":\""+newBird+"\",\"nickname\":\"New\"}";
        var first=mvc.perform(post("/api/me/pets/adopt").header("Authorization",auth).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn();
        String location=first.getResponse().getHeader("Location");
        mvc.perform(get(location).header("Authorization",auth)).andExpect(status().isOk());
        mvc.perform(post("/api/me/pets/adopt").header("Authorization",auth).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(header().string("Location",location)).andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post("/api/me/pets/adopt").header("Authorization",auth).contentType("application/json").content(body.replace(id,key())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));
    }

}
