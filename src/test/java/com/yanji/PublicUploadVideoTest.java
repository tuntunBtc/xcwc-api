package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.support.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"management-upload.public-base-url=https://assets.example.test","management-upload.video-enabled=true"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class PublicUploadVideoTest {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired RegistrationService users;@Autowired ObjectMapper json;
    @MockitoBean PublicUploadStore store;@MockitoBean PublicUploadVerifier verifier;
    @TempDir Path directory;
    String admin;Map<String,Path> objects;byte[] clip;
    @BeforeEach void setup() throws Exception {
        reset(store,verifier);objects=new ConcurrentHashMap<>();
        long actor=Math.abs(UUID.randomUUID().getMostSignificantBits()%1000000000L)+1000;
        db.update("INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag) VALUES(?,?,?,?,'0','0')",actor,"video_"+actor,"fixture","unused");
        db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",actor);
        String token="adm_"+users.randomKey(32);admin="Bearer "+token;
        db.update("INSERT INTO admin_session(token_hash,user_id,expires_at) VALUES(?,?,?)",RegistrationService.digest(token),actor,java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)));
        try(var in=getClass().getResourceAsStream("/public-upload-fixture.mp4")){clip=in.readAllBytes();}
        doAnswer(call->{String name=call.getArgument(0);Path saved=directory.resolve(name);Files.copy((Path)call.getArgument(1),saved,StandardCopyOption.REPLACE_EXISTING);objects.put(name,saved);return null;}).when(store).putFile(anyString(),any(),anyString());
        when(store.downloadTo(anyString(),any())).thenAnswer(call->{Path source=objects.get(call.getArgument(0));if(source==null)return false;Files.copy(source,call.getArgument(1),StandardCopyOption.REPLACE_EXISTING);return true;});
        doAnswer(call->{String path=java.net.URI.create(call.getArgument(0)).getPath();mvc.perform(get(path).header("Range","bytes=0-15")).andExpect(status().isPartialContent()).andExpect(header().string("Cache-Control","no-store"));return null;}).when(verifier).verifyFile(anyString(),any(),anyString());
    }
    Path padded(long size) throws Exception {
        Path path=Files.createTempFile(directory,"padded-", ".mp4");
        try(var file=new RandomAccessFile(path.toFile(),"rw")){
            file.write(clip);file.writeInt((int)(size-clip.length));file.writeBytes("free");file.setLength(size);
        }
        return path;
    }
    MockMultipartFile streamed(Path path,boolean dishonest) {
        return new MockMultipartFile("file","wrong.html","text/html",new byte[]{1}){
            @Override public long getSize(){try{return dishonest?1:Files.size(path);}catch(IOException e){throw new UncheckedIOException(e);}}
            @Override public InputStream getInputStream() throws IOException{return Files.newInputStream(path);}
            @Override public byte[] getBytes(){throw new AssertionError("Upload must not request a whole-file byte array");}
        };
    }
    String upload(MockMultipartFile file) throws Exception {
        var result=mvc.perform(multipart("/admin/v1/oss/uploads").file(file).header("Authorization",admin))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.mime").value("video/mp4"))
            .andExpect(jsonPath("$.width").value(160)).andExpect(jsonPath("$.height").value(120)).andReturn().getResponse();
        var body=json.readTree(result.getContentAsByteArray());assertThat(result.getHeader("Location")).isEqualTo(body.path("url").asText());
        return java.net.URI.create(body.path("url").asText()).getPath();
    }
    @Test void fourteenMiBAndExactFiftyMiBAreStreamedVerifiedAndReadable() throws Exception {
        for(long size:new long[]{14L*1024*1024,PublicUploadService.MAX_BYTES}){
            Path file=padded(size);String expected=PublicUploadFiles.sha256(file);String path=upload(streamed(file,false));
            String filename=path.substring(path.lastIndexOf('/')+1);
            assertThat(Files.size(objects.get(filename))).isEqualTo(size);assertThat(PublicUploadFiles.sha256(objects.get(filename))).isEqualTo(expected);
            mvc.perform(head(path)).andExpect(status().isOk()).andExpect(header().longValue("Content-Length",size)).andExpect(content().bytes(new byte[0]));
            mvc.perform(get(path).header("Range","bytes=0-31")).andExpect(status().isPartialContent()).andExpect(content().bytes(Arrays.copyOf(clip,32)))
                .andExpect(header().string("Content-Range","bytes 0-31/"+size)).andExpect(header().string("ETag","\""+expected+"\""));
            mvc.perform(get(path).header("If-None-Match","\""+expected+"\"")).andExpect(status().isNotModified());
        }
        verify(store,never()).put(anyString(),any(),anyString());verify(store,never()).find(anyString());verify(verifier,never()).verify(anyString(),any(),anyString());
    }
    @Test void hevcEightAndTenBitRemainByteIdenticalAcrossUploadAndAnonymousRead() throws Exception {
        for(String resource:new String[]{"/public-upload-fixture-hevc8.mp4","/public-upload-fixture-hevc10.mp4"}){
            byte[] original;try(var in=getClass().getResourceAsStream(resource)){original=in.readAllBytes();}
            String path=upload(new MockMultipartFile("file","untrusted.bin","application/octet-stream",original));
            String filename=path.substring(path.lastIndexOf('/')+1);
            assertThat(Files.readAllBytes(objects.get(filename))).containsExactly(original);
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().contentType("video/mp4"))
                .andExpect(content().bytes(original));
            mvc.perform(head(path)).andExpect(status().isOk()).andExpect(header().longValue("Content-Length",original.length));
            mvc.perform(get(path).header("Range","bytes=0-31")).andExpect(status().isPartialContent())
                .andExpect(content().bytes(Arrays.copyOf(original,32)));
        }
    }
    @Test void exactFiftyMiBPngUsesTheSameFileLimitWithoutWholeFileArrays() throws Exception {
        Path file=Files.createTempFile(directory,"image-", ".png");
        try(var output=Files.newOutputStream(file)){javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16,12,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);}
        try(var output=new RandomAccessFile(file.toFile(),"rw")){output.setLength(PublicUploadService.MAX_BYTES);}
        var result=mvc.perform(multipart("/admin/v1/oss/uploads").file(streamed(file,false)).header("Authorization",admin)).andExpect(status().isCreated()).andExpect(jsonPath("$.mime").value("image/png")).andExpect(jsonPath("$.size").value(PublicUploadService.MAX_BYTES)).andReturn().getResponse();
        String path=java.net.URI.create(json.readTree(result.getContentAsByteArray()).path("url").asText()).getPath();mvc.perform(head(path)).andExpect(status().isOk()).andExpect(header().longValue("Content-Length",PublicUploadService.MAX_BYTES));
    }
    @Test void fullRangeSuffixConditionalAndInvalidRangesAreConsistent() throws Exception {
        String path=upload(new MockMultipartFile("file","tiny.mp4","video/mp4",clip));
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().bytes(clip));
        mvc.perform(get(path).header("Range","bytes=-12")).andExpect(status().isPartialContent()).andExpect(content().bytes(Arrays.copyOfRange(clip,clip.length-12,clip.length)));
        mvc.perform(get(path).header("Range","bytes=10-")).andExpect(status().isPartialContent()).andExpect(content().bytes(Arrays.copyOfRange(clip,10,clip.length)));
        mvc.perform(get(path).header("Range","bytes=0-1").header("If-Range","\"old\"")).andExpect(status().isOk()).andExpect(content().bytes(clip));
        for(String range:List.of("bytes=999999-","bytes=0-1,3-4","bytes=-0","bytes=3-1","bytes=999999999999999999999-"))
            mvc.perform(get(path).header("Range",range)).andExpect(status().isRequestedRangeNotSatisfiable()).andExpect(header().string("Content-Range","bytes */"+clip.length)).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void overFiftyMiBAndLyingStreamAreRejectedBeforeOss() throws Exception {
        Path file=padded(PublicUploadService.MAX_BYTES+1L);
        for(boolean lie:List.of(false,true))mvc.perform(multipart("/admin/v1/oss/uploads").file(streamed(file,lie)).header("Authorization",admin))
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PUBLIC_UPLOAD_SIZE_INVALID"));
        verifyNoInteractions(store,verifier);
    }
    @Test void fakeCorruptAndUnsupportedContainersNeverReachOss() throws Exception {
        mvc.perform(multipart("/admin/v1/oss/uploads").file(new MockMultipartFile("file","fake.mp4","video/mp4","not video".getBytes())).header("Authorization",admin)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/admin/v1/oss/uploads").file(new MockMultipartFile("file","broken.mp4","video/mp4",Arrays.copyOf(clip,32))).header("Authorization",admin)).andExpect(status().isBadRequest());
        byte[] mov=clip.clone();System.arraycopy("qt  ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,mov,8,4);
        mvc.perform(multipart("/admin/v1/oss/uploads").file(new MockMultipartFile("file","quicktime.mov","video/quicktime",mov)).header("Authorization",admin)).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(store,verifier);
    }
    @Test void twoPublicReadsCannotStarveVerificationAndSlotsRecover() throws Exception {
        String path=upload(new MockMultipartFile("file","tiny.mp4","video/mp4",clip));String filename=path.substring(path.lastIndexOf('/')+1);
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        when(store.downloadTo(eq(filename),any())).thenAnswer(call->{entered.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();Files.copy(objects.get(filename),call.getArgument(1),StandardCopyOption.REPLACE_EXISTING);return true;});
        try(var executor=Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->mvc.perform(get(path)).andReturn().getResponse().getStatus());var second=executor.submit(()->mvc.perform(get(path)).andReturn().getResponse().getStatus());
            try{
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();mvc.perform(get(path)).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1"));
                upload(new MockMultipartFile("file","other.mp4","video/mp4",clip));
            }finally{release.countDown();}
            assertThat(first.get()).isEqualTo(200);assertThat(second.get()).isEqualTo(200);
        }
        mvc.perform(get(path)).andExpect(status().isOk());
    }
}
