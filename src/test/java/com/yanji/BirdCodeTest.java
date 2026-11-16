package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.DefaultApplicationArguments;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BirdCodeTest {
    @Autowired BirdFactory factory;
    @Autowired BirdSnowflake snowflake;
    @Autowired RegistrationService registration;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    @TempDir Path temp;
    long now;
    BirdFactory.Spec spec() { return new BirdFactory.Spec("HZ","HY","factory_yellow","原色","/assets/birds/yellow.png","HZ-HY-TEST"); }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM checkin_work"); jdbc.update("DELETE FROM checkin_reward_source"); jdbc.update("DELETE FROM checkin_reward_day"); jdbc.update("DELETE FROM bird_binding"); jdbc.update("DELETE FROM checkin_work"); jdbc.update("DELETE FROM points_ledger"); jdbc.update("DELETE FROM user_skin");
        jdbc.update("DELETE FROM bird"); jdbc.update("DELETE FROM auth_session"); jdbc.update("DELETE FROM app_user");
        jdbc.update("UPDATE bird_snowflake_state SET last_millis=0,sequence_no=0 WHERE allocator_id=1");
        now=Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
        when(clock.millis()).thenAnswer(invocation -> now);
    }
    @Test void generatesCorrectFormatAndQueriesPublicMetadata() throws Exception {
        var a=factory.create(spec()); var b=factory.create(spec());
        assertThat(a.id()).matches("[a-f0-9]{32}");
        assertThat(a.birdCode()).matches("HZ-HY-[1-9][0-9]{0,18}").hasSizeLessThanOrEqualTo(32).isNotEqualTo(b.birdCode());
        assertThat(a.linkSuffix()).isEqualTo("/register.html?birdCode="+a.birdCode());
        String suffix=a.birdCode().substring(6);
        assertThat((Long.parseLong(suffix)>>>22)+BirdSnowflake.EPOCH_MILLIS).isEqualTo(now);
        var found=registration.findByCode(a.birdCode());
        assertThat(found.id()).isEqualTo(a.id()); assertThat(found.ipCode()).isEqualTo("HY");
        assertThat(found.cityName()).isEqualTo("杭州"); assertThat(found.typeName()).isEqualTo("小黄莺");
        assertThat(found.skinId()).isEqualTo("factory_yellow"); assertThat(found.image()).isEqualTo("/assets/birds/yellow.png");
        mvc.perform(get("/api/birds/by-code/"+a.birdCode()).header("If-None-Match","*")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("ETag"))
            .andExpect(jsonPath("$.birdCode").value(a.birdCode())).andExpect(jsonPath("$.id").value(a.id()))
            .andExpect(jsonPath("$.name").value("小黄莺")).andExpect(jsonPath("$.ownerId").doesNotExist())
            .andExpect(jsonPath("$.claimHash").doesNotExist()).andExpect(jsonPath("$.claimCode").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT owner_id FROM bird WHERE id=?",String.class,a.id())).isNull();
    }
    @Test void oldIdsRemainUnchangedAndLookupCannotGuessMissingCodes() throws Exception {
        registration.seedDemo();
        mvc.perform(get("/api/birds/00000000000000000000000000000001")).andExpect(status().isOk())
            .andExpect(jsonPath("$.birdCode").isEmpty()).andExpect(jsonPath("$.name").value("燕叽"));
        mvc.perform(get("/api/birds/by-code/HZ-HY-1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/birds/by-code/hz-HY-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/birds/by-code/HZ-HY-9223372036854775808")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/birds/by-code/HZ-HY-01")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bird",Integer.class)).isEqualTo(3);
    }
    @Test void rejectsInactiveEntitiesAndUnconfirmedManufacturingData() throws Exception {
        var a=factory.create(spec());
        jdbc.update("UPDATE bird SET active=FALSE WHERE id=?",a.id());
        mvc.perform(get("/api/birds/by-code/"+a.birdCode()).header("If-None-Match","*")).andExpect(status().isGone())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("ETag"))
            .andExpect(jsonPath("$.code").value("BIRD_INACTIVE"));
        mvc.perform(get("/api/birds/"+a.id())).andExpect(status().isGone());
        for(var bad:List.of(new BirdFactory.Spec("ZZ","HY","yellow","原色","/assets/yellow.png","batch"),
            new BirdFactory.Spec("HZ","YL","yellow","原色","/assets/yellow.png","batch"),
            new BirdFactory.Spec("HZ","HY","yellow","原色","https://example.com/a.png","batch")))
            assertThatThrownBy(()->factory.create(bad)).isInstanceOf(RegistrationService.Failure.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bird",Integer.class)).isEqualTo(1);
    }
    @Test void persistedStateSurvivesNewGeneratorInstanceAndOuterRollback() {
        String first=snowflake.nextId();
        var restarted=new BirdSnowflake(jdbc,clock,transactions);
        String second=restarted.nextId();
        assertThat(Long.parseLong(second)).isGreaterThan(Long.parseLong(first));
        String[] rolledBack=new String[1];
        new TransactionTemplate(transactions).executeWithoutResult(status -> { rolledBack[0]=snowflake.nextId(); status.setRollbackOnly(); });
        assertThat(Long.parseLong(snowflake.nextId())).isGreaterThan(Long.parseLong(rolledBack[0]));
    }
    @Test void fingerprintedImagesAreValidWithoutAllowingTraversalOrUrlComponents() {
        String image="/assets/optimized/nest/felt/emotions/happy.0123456789ab.webp";
        var base=spec();
        var manufactured=factory.create(new BirdFactory.Spec(base.city(),base.ip(),base.skinId(),base.skinName(),image,base.batch()));
        assertThat(registration.findByCode(manufactured.birdCode()).imagePath()).isEqualTo(image);
        assertThat(manufactured.linkSuffix()).isEqualTo("/register.html?birdCode="+manufactured.birdCode());
        for(String invalid:List.of("/assets/../private.png","/assets//bird.webp","/assets/./bird.webp",
                "/assets/a/../../bird.png","/assets/bird..webp","/assets/bird.webp?size=1",
                "/assets/bird.webp#fragment","/assets/%2e%2e/bird.png","https://example.com/bird.webp")) {
            assertThatThrownBy(()->factory.create(new BirdFactory.Spec(base.city(),base.ip(),base.skinId(),base.skinName(),invalid,base.batch())))
                .as(invalid).isInstanceOfSatisfying(RegistrationService.Failure.class,
                    error->assertThat(error.code).isEqualTo("INVALID_MANUFACTURING_SPEC"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bird",Integer.class)).isEqualTo(1);
    }
    @Test void clockRollbackAndSequenceExhaustionFailWithoutReusingIds() {
        snowflake.nextId(); now--;
        assertThatThrownBy(()->snowflake.nextId()).isInstanceOfSatisfying(RegistrationService.Failure.class,e->assertThat(e.code).isEqualTo("SNOWFLAKE_CLOCK_ROLLBACK"));
        now++; jdbc.update("UPDATE bird_snowflake_state SET sequence_no=4095 WHERE allocator_id=1");
        assertThatThrownBy(()->snowflake.nextId()).isInstanceOfSatisfying(RegistrationService.Failure.class,e->assertThat(e.code).isEqualTo("SNOWFLAKE_SEQUENCE_EXHAUSTED"));
        now++; assertThat(snowflake.nextId()).isNotBlank();
    }
    @Test void concurrentGeneratorsShareOnePersistentSequence() throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        var results=new ArrayList<String>();
        try(var executor=Executors.newFixedThreadPool(3)) {
            var jobs=List.of(1,2,3).stream().map(n->executor.submit(()->{
                start.await(); var generator=new BirdSnowflake(jdbc,clock,transactions);
                List<String> ids=new ArrayList<>(); for(int i=0;i<20;i++)ids.add(generator.nextId());return ids;
            })).toList();
            start.countDown();for(var job:jobs)results.addAll(job.get(15,TimeUnit.SECONDS));
        }
        assertThat(new HashSet<>(results)).hasSize(60);
        assertThat(jdbc.queryForObject("SELECT sequence_no FROM bird_snowflake_state WHERE allocator_id=1",Integer.class)).isEqualTo(59);
    }
    @Test void offlineCommandExportsFivePublicLinksWithoutCredentialsAndNeverOverwrites() throws Exception {
        Path output=temp.resolve("public-codes.csv");
        var command=new ProvisionCommand(registration,factory);
        ReflectionTestUtils.setField(command,"count",5);
        ReflectionTestUtils.setField(command,"webType","none");
        ReflectionTestUtils.setField(command,"output",output.toString());
        ReflectionTestUtils.setField(command,"city","HZ");
        ReflectionTestUtils.setField(command,"ip","HY");
        ReflectionTestUtils.setField(command,"skinId","yellow");
        ReflectionTestUtils.setField(command,"skinName","原色");
        ReflectionTestUtils.setField(command,"image","/assets/yellow.png");
        ReflectionTestUtils.setField(command,"batch","TEST-HZ-HY-5");
        command.run(new DefaultApplicationArguments(new String[0]));
        var lines=Files.readAllLines(output);
        assertThat(lines).hasSize(6);
        assertThat(lines.getFirst()).isEqualTo("bird_id,bird_code,link_suffix");
        for(String line:lines.subList(1,lines.size())) {
            var cells=line.split(",");assertThat(cells).hasSize(3);
            assertThat(registration.findByCode(cells[1]).id()).isEqualTo(cells[0]);
            assertThat(cells[2]).isEqualTo("/register.html?birdCode="+cells[1]);
        }
        assertThat(Files.readString(output)).doesNotContain("claim_code","claimCode","claim_hash");
        assertThatThrownBy(()->command.run(new DefaultApplicationArguments(new String[0])))
            .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bird",Integer.class)).isEqualTo(5);
    }

    @Test void databaseRejectsDuplicatePublicCode() {
        var a=factory.create(spec());var b=factory.create(spec());
        assertThatThrownBy(()->jdbc.update("UPDATE bird SET bird_code=? WHERE id=?",a.birdCode(),b.id()))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(registration.findByCode(a.birdCode()).id()).isEqualTo(a.id());
    }
}
