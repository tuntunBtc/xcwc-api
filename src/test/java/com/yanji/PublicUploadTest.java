package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="management-upload.public-base-url=https://assets.example.test")
@AutoConfigureMockMvc @ActiveProfiles("test")
class PublicUploadTest {
    @Autowired MockMvc mvc;
    @Autowired PublicUploadService uploads;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired RegistrationService users;
    @MockitoBean PublicUploadStore store;
    @MockitoBean PublicUploadVerifier verifier;
    String admin, reader, customer;
    byte[] png;
    Map<String,byte[]> objects;

    String operator(int role) {
        long id=Math.abs(UUID.randomUUID().getMostSignificantBits()%1000000000L)+1000;
        db.update("INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag,create_time) VALUES(?,?,?,?, '0','0',CURRENT_TIMESTAMP)",
            id,"upload_"+id,"fixture","unused");
        db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role);
        String token="adm_"+users.randomKey(32);
        db.update("INSERT INTO admin_session(token_hash,user_id,expires_at) VALUES(?,?,?)",
            RegistrationService.digest(token),id,java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)));
        return "Bearer "+token;
    }

    @BeforeEach void setup() throws Exception {
        reset(store,verifier);
        admin=operator(1);reader=operator(4);
        customer="Bearer "+users.register("pub_"+users.randomKey(6),"fixture-password-123").token();
        var stream=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12,10,BufferedImage.TYPE_INT_RGB),"png",stream);
        png=stream.toByteArray();
        objects=new ConcurrentHashMap<>();
        doAnswer(call->{objects.put(call.getArgument(0),Files.readAllBytes(call.getArgument(1)));return null;})
            .when(store).putFile(anyString(),any(),anyString());
        when(store.downloadTo(anyString(),any())).thenAnswer(call->{byte[] data=objects.get(call.getArgument(0));if(data==null)return false;Files.write(call.getArgument(1),data);return true;});
    }

    MockMultipartFile picture() { return new MockMultipartFile("file","spoof-name.html","text/html",png); }

    @Test void publicUrlIsReturnedOnlyAfterAnonymousVerificationAndHasDurableMetadata() throws Exception {
        doAnswer(call->{
            String url=call.getArgument(0);
            String path=java.net.URI.create(url).getPath();
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().bytes(png))
                .andExpect(header().stringValues("Cache-Control","no-store"));
            return null;
        }).when(verifier).verifyFile(anyString(),any(),anyString());
        var response=mvc.perform(multipart("/admin/v1/oss/uploads").file(picture()).header("Authorization",admin))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.mime").value("image/png"))
            .andExpect(jsonPath("$.width").value(12)).andReturn().getResponse();
        var result=json.readTree(response.getContentAsByteArray());
        String url=result.path("url").asText();
        assertThat(url).startsWith("https://assets.example.test/bird/v1/oss/uploads/").endsWith(".png").doesNotContain("?");
        assertThat(response.getHeader("Location")).isEqualTo(url);
        mvc.perform(get(java.net.URI.create(url).getPath())).andExpect(status().isOk())
            .andExpect(content().bytes(png)).andExpect(header().stringValues("Cache-Control","public, max-age=31536000, immutable"))
            .andExpect(content().contentType("image/png")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        String id=result.path("id").asText();
        assertThat(db.queryForObject("SELECT status FROM public_operational_upload WHERE id=?",String.class,id)).isEqualTo("READY");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_oper_log WHERE title='UPLOAD_PUBLIC_OSS' AND oper_param LIKE ? AND status=0",
            Integer.class,id+"%")).isEqualTo(1);
        verify(store).putFile(eq(id+".png"),any(Path.class),eq("image/png"));
        verify(verifier).verifyFile(eq(url),any(Path.class),eq("image/png"));
    }

    @Test void jpegAndWebpAreDecodedAndServedWithTheirActualMime() throws Exception {
        var jpeg=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12,10,BufferedImage.TYPE_INT_RGB),"jpeg",jpeg);
        byte[] webp;
        try(var input=getClass().getResourceAsStream("/public-upload-fixture.webp")) {webp=input.readAllBytes();}
        for(var image:Map.of("image/jpeg",jpeg.toByteArray(),"image/webp",webp).entrySet()) {
            var response=mvc.perform(multipart("/admin/v1/oss/uploads")
                    .file(new MockMultipartFile("file","wrong.png","text/html",image.getValue()))
                    .header("Authorization",admin)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.mime").value(image.getKey())).andReturn().getResponse();
            var body=json.readTree(response.getContentAsByteArray());
            mvc.perform(get(java.net.URI.create(body.path("url").asText()).getPath()))
                .andExpect(status().isOk()).andExpect(content().contentType(image.getKey()))
                .andExpect(content().bytes(image.getValue()));
        }
    }

    @Test void dimensionsAndActualStreamLengthAreBoundedBeforeStorage() throws Exception {
        byte[] hugeDimensions=png.clone();
        var bytes=java.nio.ByteBuffer.wrap(hugeDimensions);
        bytes.putInt(16,8192);bytes.putInt(20,8192);
        var crc=new java.util.zip.CRC32();crc.update(hugeDimensions,12,17);
        bytes.putInt(29,(int)crc.getValue());
        mvc.perform(multipart("/admin/v1/oss/uploads")
                .file(new MockMultipartFile("file","huge.png","image/png",hugeDimensions))
                .header("Authorization",admin)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PUBLIC_UPLOAD_DIMENSIONS_INVALID"));
        var dishonest=new MockMultipartFile("file","large.png","image/png",new byte[PublicUploadService.MAX_BYTES+1]) {
            @Override public long getSize() {return 1;}
        };
        mvc.perform(multipart("/admin/v1/oss/uploads").file(dishonest).header("Authorization",admin))
            .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(store,verifier);
    }

    @Test void uploadRequiresEditorPermissionAndNeverExposesPrivatePaths() throws Exception {
        mvc.perform(multipart("/admin/v1/oss/uploads").file(picture())).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/admin/v1/oss/uploads").file(picture()).header("Authorization",customer)).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/admin/v1/oss/uploads").file(picture()).header("Authorization",reader)).andExpect(status().isForbidden());
        for(String filename:List.of("originals","invalid.png","00000000000000000000000000000000.png")) {
            mvc.perform(get("/bird/v1/oss/uploads/"+filename)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/oss/uploads")).andExpect(status().isNotFound());
        verifyNoInteractions(store,verifier);
    }

    @Test void fakeCorruptMissingAndOversizeFilesNeverReachOss() throws Exception {
        mvc.perform(multipart("/admin/v1/oss/uploads").header("Authorization",admin))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PUBLIC_UPLOAD_FILE_REQUIRED"));
        mvc.perform(multipart("/admin/v1/oss/uploads")
                .file(new MockMultipartFile("file","fake.png","image/png","<svg onload='x'>".getBytes()))
                .header("Authorization",admin)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/admin/v1/oss/uploads")
                .file(new MockMultipartFile("file","bad.png","image/png",Arrays.copyOf(png,12)))
                .header("Authorization",admin)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/admin/v1/oss/uploads")
                .file(new MockMultipartFile("file","large.png","image/png",new byte[PublicUploadService.MAX_BYTES+1]))
                .header("Authorization",admin)).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(store,verifier);
    }

    @Test void storageAndAnonymousVerificationFailuresRetainFailedRecordsWithoutReturningUrls() throws Exception {
        doThrow(new IOException("fixture")).when(store).putFile(anyString(),any(),anyString());
        mvc.perform(multipart("/admin/v1/oss/uploads").file(picture()).header("Authorization",admin))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("OSS_UPLOAD_FAILED"))
            .andExpect(jsonPath("$.url").doesNotExist());
        verifyNoInteractions(verifier);
        reset(store);
        doAnswer(call->{objects.put(call.getArgument(0),Files.readAllBytes(call.getArgument(1)));return null;})
            .when(store).putFile(anyString(),any(),anyString());
        when(store.downloadTo(anyString(),any())).thenAnswer(call->{byte[] data=objects.get(call.getArgument(0));if(data==null)return false;Files.write(call.getArgument(1),data);return true;});
        doThrow(new IOException("not anonymously readable")).when(verifier).verifyFile(anyString(),any(),anyString());
        mvc.perform(multipart("/admin/v1/oss/uploads").file(picture()).header("Authorization",admin))
            .andExpect(status().isServiceUnavailable());
        for(String filename:objects.keySet()) {
            mvc.perform(get("/bird/v1/oss/uploads/"+filename)).andExpect(status().isNotFound());
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public_operational_upload WHERE status='FAILED' AND actor_id=(SELECT user_id FROM sys_user WHERE user_name=?)",
            Integer.class,"upload_"+actorId(admin))).isEqualTo(2);
    }

    private long actorId(String auth) {
        return db.queryForObject("SELECT user_id FROM admin_session WHERE token_hash=?",Long.class,
            RegistrationService.digest(auth.substring(7)));
    }

    @Test void corruptOssReadbackAndExpiredVerificationDoNotBecomePublic() throws Exception {
        when(store.downloadTo(anyString(),any())).thenAnswer(call->{Files.write(call.getArgument(1),new byte[]{1});return true;});
        mvc.perform(multipart("/admin/v1/oss/uploads").file(picture()).header("Authorization",admin))
            .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(verifier);
        String filename=objects.keySet().iterator().next();
        db.update("UPDATE public_operational_upload SET status='VERIFYING',verification_expires_at=? WHERE filename=?",
            java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),filename);
        mvc.perform(get("/bird/v1/oss/uploads/"+filename)).andExpect(status().isNotFound());
    }

    @Test void concurrentUploadReceivesBusyResponseAndCannotHoldAnotherImageDecode() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return null;})
            .when(verifier).verifyFile(anyString(),any(),anyString());
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->mvc.perform(multipart("/admin/v1/oss/uploads").file(picture())
                .header("Authorization",admin)).andReturn().getResponse().getStatus());
            assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();
            mvc.perform(multipart("/admin/v1/oss/uploads").file(picture()).header("Authorization",admin))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","1"))
                .andExpect(jsonPath("$.code").value("OSS_UPLOAD_BUSY"));
            release.countDown();assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(201);
        } finally {release.countDown();executor.shutdownNow();}
    }
}
