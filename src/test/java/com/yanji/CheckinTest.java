package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckinTest {
    static final Path DIRECTORY;
    static { try { DIRECTORY=Files.createTempDirectory("xcwc-checkin-tests-"); }catch(Exception e){throw new RuntimeException(e);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("app.checkins.directory",DIRECTORY::toString);
        registry.add("spring.datasource.url",()->System.getenv().getOrDefault("TEST_DB_URL","jdbc:h2:mem:checkin_test;MODE=MySQL;DB_CLOSE_DELAY=-1"));
    }
    @Autowired CheckinService checkins;
    @Autowired RegistrationService registration;
    @Autowired PointsService points;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean Clock clock;
    String auth,other,user;List<String> birds;MockMultipartFile original,image;
    String key(){return UUID.randomUUID().toString();}
    MockMultipartFile picture(String part,int w,int h) throws Exception {
        var stream=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB),"jpeg",stream);
        return new MockMultipartFile(part,"image.jpg","image/jpeg",stream.toByteArray());
    }
    CheckinService.Submission request(String id,List<String> selected){return new CheckinService.Submission(id,selected,"natural","西湖");}
    @BeforeEach void setup() throws Exception {
        org.mockito.Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-11-02T00:00:00Z"));
        jdbc.update("DELETE FROM checkin_work"); jdbc.update("DELETE FROM checkin_reward_source"); jdbc.update("DELETE FROM checkin_reward_day"); jdbc.update("DELETE FROM bird_binding"); jdbc.update("DELETE FROM checkin_work");jdbc.update("DELETE FROM points_ledger");jdbc.update("DELETE FROM user_skin");jdbc.update("DELETE FROM bird");jdbc.update("DELETE FROM auth_session");jdbc.update("DELETE FROM app_user");
        auth="Bearer "+registration.register("checkin_owner","password-12345").token();user=registration.userId(auth);
        other="Bearer "+registration.register("checkin_other","password-12345").token();
        jdbc.update("UPDATE app_user SET initial_reward_received=TRUE");
        birds=new ArrayList<>();for(int i=0;i<4;i++){var provision=registration.provision();registration.claim(auth,provision.id(),provision.claimCode(),"小鸟"+i);birds.add(provision.id());}
        original=picture("original",100,100);image=picture("image",1000,1010);
    }
    @AfterEach void files() throws Exception {try(var paths=Files.list(DIRECTORY)){for(Path file:paths.toList())Files.deleteIfExists(file);}}
    @AfterAll static void cleanup() throws Exception {Files.deleteIfExists(DIRECTORY);}
    @Test void threeBirdSuccessRetryAndCrossAccountIsolation() throws Exception {
        var submission=request(key(),birds.subList(0,3));var first=checkins.create(auth,submission,original,image);
        assertThat(first.birdIds()).hasSize(3);assertThat(first.reward()).isEqualTo(1);
        assertThat(checkins.create(auth,submission,original,image).id()).isEqualTo(first.id());
        assertThat(points.wallet(auth).balance()).isEqualTo(1);assertThat(points.wallet(auth).earnedToday()).isZero();
        assertThat(points.history(auth,Long.MAX_VALUE)).singleElement().satisfies(e->{assertThat(e.operation()).isEqualTo("CHECKIN_REWARD");assertThat(e.targetId()).isEqualTo(first.id());});
        assertThat(checkins.image(auth,first.id(),false).bytes()).isEqualTo(image.getBytes());
        mvc.perform(get(first.imageUrl()).header("Authorization",other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/checkins/"+first.id()).header("Authorization",other)).andExpect(status().isNotFound());
        mvc.perform(get(first.originalUrl())).andExpect(status().isUnauthorized());
        assertThat(checkins.history(other,null,20).items()).isEmpty();
        try(var paths=Files.list(DIRECTORY)){assertThat(paths.count()).isEqualTo(2);}
    }
    @Test void validationNeverPays() {
        assertThatThrownBy(()->checkins.create(auth,request(key(),List.of()),original,image)).isInstanceOf(RegistrationService.Failure.class);
        assertThatThrownBy(()->checkins.create(auth,request(key(),birds),original,image)).isInstanceOf(RegistrationService.Failure.class);
        assertThatThrownBy(()->checkins.create(other,request(key(),birds.subList(0,1)),original,image)).isInstanceOf(RegistrationService.Failure.class);
        assertThatThrownBy(()->checkins.create(auth,request(key(),birds.subList(0,1)),original,new MockMultipartFile("image","x.jpg","image/jpeg",new byte[]{1,2,3}))).isInstanceOf(RegistrationService.Failure.class);
        assertThat(points.wallet(auth).balance()).isZero();assertThat(checkins.history(auth,null,20).total()).isZero();
    }
    @Test void requestConflictAndLedgerConflictRollBackFilesAndWork() throws Exception {
        String id=key();checkins.create(auth,request(id,birds.subList(0,1)),original,image);
        assertThatThrownBy(()->checkins.create(auth,request(id,birds.subList(1,2)),original,image)).isInstanceOf(RegistrationService.Failure.class);
        String petRequest=key();points.pet(auth,birds.getFirst(),petRequest);
        assertThatThrownBy(()->checkins.create(auth,request(petRequest,birds.subList(0,1)),picture("original",101,100),image)).isInstanceOf(RegistrationService.Failure.class);
        assertThat(checkins.history(auth,null,20).total()).isEqualTo(1);
        try(var paths=Files.list(DIRECTORY)){assertThat(paths.count()).isEqualTo(2);}
    }
    @Test void concurrentRetryOnlyPaysOnce() throws Exception {
        String id=key();var ready=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->{ready.await();return checkins.create(auth,request(id,birds.subList(0,3)),original,image);});
            var b=executor.submit(()->{ready.await();return checkins.create(auth,request(id,birds.subList(0,3)),original,image);});
            ready.countDown();assertThat(a.get(15,TimeUnit.SECONDS).id()).isEqualTo(b.get(15,TimeUnit.SECONDS).id());
        }
        assertThat(points.wallet(auth).balance()).isEqualTo(1);assertThat(checkins.history(auth,null,20).total()).isEqualTo(1);
    }
    @Test void paginationIsStableForIdenticalTimesAndNewInsertions() {
        for(int i=0;i<5;i++)checkins.create(auth,request(key(),birds.subList(0,1)),original,image);
        jdbc.update("UPDATE checkin_work SET created_at=?",java.sql.Timestamp.from(Instant.parse("2026-10-30T08:00:00Z")));
        var first=checkins.history(auth,null,2);checkins.create(auth,request(key(),birds.subList(0,1)),original,image);
        var second=checkins.history(auth,first.nextCursor(),2);var third=checkins.history(auth,second.nextCursor(),2);
        var ids=new ArrayList<String>();first.items().forEach(x->ids.add(x.id()));second.items().forEach(x->ids.add(x.id()));third.items().forEach(x->ids.add(x.id()));
        assertThat(ids).hasSize(5).doesNotHaveDuplicates();assertThat(ids).isSortedAccordingTo(Comparator.reverseOrder());assertThat(third.nextCursor()).isNull();
    }
    @Test void rewardsDoNotUsePetQuotaAndSettlementFailureCleansFiles() throws Exception {
        for(int i=0;i<11;i++)checkins.create(auth,request(key(),birds.subList(0,1)),original,image);
        assertThat(points.wallet(auth).balance()).isEqualTo(1);assertThat(points.wallet(auth).earnedToday()).isZero();
        jdbc.update("UPDATE app_user SET points_balance=? WHERE id=?",Long.MAX_VALUE,user);
        assertThatThrownBy(()->checkins.create(auth,request(key(),birds.subList(0,1)),picture("original",102,100),image)).isInstanceOf(ArithmeticException.class);
        assertThat(checkins.history(auth,null,20).total()).isEqualTo(11);
        try(var paths=Files.list(DIRECTORY)){assertThat(paths.count()).isEqualTo(22);}
    }
    @Test void capAndOriginalDedupStillAllowSavingAndDoNotRetryZeroRewardsTomorrow() throws Exception {
        var first=checkins.create(auth,request(key(),birds.subList(0,1)),original,image);
        var duplicate=checkins.create(auth,request(key(),birds.subList(0,1)),original,image);
        assertThat(first.reward()).isEqualTo(1);assertThat(duplicate.reward()).isZero();
        assertThat(checkins.create(auth,request(key(),birds.subList(0,1)),picture("original",101,100),image).reward()).isEqualTo(1);
        assertThat(checkins.create(auth,request(key(),birds.subList(0,1)),picture("original",102,100),image).reward()).isEqualTo(1);
        var noReward=request(key(),birds.subList(0,1));var source=picture("original",103,100);
        var capped=checkins.create(auth,noReward,source,image);assertThat(capped.reward()).isZero();
        org.mockito.Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-11-03T00:00:00Z"));
        assertThat(checkins.create(auth,noReward,source,image).reward()).isZero();
        assertThat(checkins.create(auth,request(key(),birds.subList(0,1)),original,image).reward()).isZero();
        assertThat(checkins.create(auth,request(key(),birds.subList(0,1)),picture("original",104,100),image).reward()).isEqualTo(1);
        assertThat(points.wallet(auth).balance()).isEqualTo(4);
    }
    @Test void concurrentNewSourcesCannotExceedThreeDailyRewards() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var sources=new ArrayList<MockMultipartFile>();for(int i=0;i<4;i++)sources.add(picture("original",110+i,100));
            var jobs=sources.stream().map(source->pool.submit(()->{start.await();return checkins.create(auth,request(key(),birds.subList(0,1)),source,image);})).toList();
            start.countDown();int total=0;for(var job:jobs)total+=job.get(20,TimeUnit.SECONDS).reward();assertThat(total).isEqualTo(3);
        }
        assertThat(points.wallet(auth).balance()).isEqualTo(3);assertThat(checkins.history(auth,null,20).total()).isEqualTo(4);
    }
    @Test void multipartContractAndImageStatus() throws Exception {
        String metadata=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request(key(),birds.subList(0,3)));
        mvc.perform(multipart("/api/checkins").file(original).file(image).file(new MockMultipartFile("metadata","metadata.json","application/json",metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8))).header("Authorization",auth))
            .andExpect(status().isCreated()).andExpect(header().exists("Location")).andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.reward").value(1));
        String location="/api/checkins/"+checkins.history(auth,null,20).items().getFirst().id();
        mvc.perform(multipart("/api/checkins").file(original).file(image).file(new MockMultipartFile("metadata","metadata.json","application/json",metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8))).header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(header().string("Location",location));
        mvc.perform(get(location).header("Authorization",auth)).andExpect(status().isOk());
        mvc.perform(get("/api/me/checkins").header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/checkin-catalog")).andExpect(status().isOk());
    }
    @Test void diskFailureNeverPaysOrCreatesWork() throws Exception {
        Files.delete(DIRECTORY);Files.writeString(DIRECTORY,"not a directory");
        try {
            assertThatThrownBy(()->checkins.create(auth,request(key(),birds.subList(0,1)),original,image)).isInstanceOf(RegistrationService.Failure.class);
            assertThat(points.wallet(auth).balance()).isZero();assertThat(checkins.history(auth,null,20).total()).isZero();
        } finally {Files.delete(DIRECTORY);Files.createDirectory(DIRECTORY);}
    }
    @Test void pngOriginalFilenameMatchesItsMimeWhileComposedPhotoStaysJpeg() throws Exception {
        var stream=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(120,100,BufferedImage.TYPE_INT_RGB),"png",stream);
        byte[] png=stream.toByteArray();
        var source=new MockMultipartFile("original","original.png","image/png",png);
        var work=checkins.create(auth,request(key(),birds.subList(0,1)),source,image);
        mvc.perform(get(work.originalUrl()).header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
            .andExpect(header().string("Content-Disposition","inline; filename=\"checkin.png\""))
            .andExpect(content().bytes(png));
        mvc.perform(get(work.imageUrl()).header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"))
            .andExpect(header().string("Content-Disposition","inline; filename=\"checkin.jpg\""))
            .andExpect(content().bytes(image.getBytes()));
        mvc.perform(get(work.originalUrl()).header("Authorization",other)).andExpect(status().isNotFound());
        mvc.perform(get(work.originalUrl())).andExpect(status().isUnauthorized());
    }
}
