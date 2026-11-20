package com.yanji;

import static org.assertj.core.api.Assertions.*;
import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PublicUploadHttpTest.FixtureConfiguration.class)
class PublicUploadHttpTest {
    static Fixture fixture;
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired RegistrationService users;
    @Autowired ObjectMapper json;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        try { fixture=new Fixture(); }
        catch(Exception e) {throw new IllegalStateException(e);}
        registry.add("management-upload.public-base-url",()->fixture.origin());
        registry.add("management-upload.video-enabled",()->true);
    }

    @TestConfiguration static class FixtureConfiguration {
        @Bean(destroyMethod="close") PublicUploadStore fixturePublicStore() {
            var config=new ClientBuilderConfiguration();
            config.setSignatureVersion(com.aliyun.oss.common.comm.SignVersion.V4);
            OSS client=OSSClientBuilder.create().endpoint("http://127.0.0.1:"+fixture.oss.getAddress().getPort())
                .region("cn-hangzhou").credentialsProvider(new DefaultCredentialProvider("fixture-id","fixture-secret"))
                .clientConfiguration(config).build();
            return new OssPublicUploadStore(client,"fixture-public-images");
        }
        @Bean(destroyMethod="close") PublicUploadVerifier fixtureVerifier() {
            return new HttpsPublicUploadVerifier(HttpClient.newBuilder().sslContext(fixture.ssl)
                .followRedirects(HttpClient.Redirect.NEVER).build(),Duration.ofSeconds(10));
        }
    }

    @AfterAll static void cleanup() throws Exception { if(fixture!=null)fixture.close(); }

    @Test void realHttpMultipartUsesRealSdkAndReturnedHttpsUrlIsAnonymousAndByteIdentical() throws Exception {
        fixture.applicationPort=port;
        long actor=Math.abs(UUID.randomUUID().getMostSignificantBits()%1000000000L)+1000;
        db.update("INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag) VALUES(?,?,?,?,'0','0')",
            actor,"tls_"+actor,"fixture","unused");
        db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",actor);
        String token="adm_"+users.randomKey(32);
        db.update("INSERT INTO admin_session(token_hash,user_id,expires_at) VALUES(?,?,?)",
            RegistrationService.digest(token),actor,java.sql.Timestamp.from(Instant.now().plusSeconds(600)));
        var stream=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB),"png",stream);
        byte[] bytes=stream.toByteArray();
        String boundary="fixture"+users.randomKey(8);
        var multipart=new ByteArrayOutputStream();
        multipart.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        multipart.write(bytes);
        multipart.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        try(var client=HttpClient.newBuilder().sslContext(fixture.ssl).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/admin/v1/oss/uploads"))
                .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray())).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).as(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(201);
            var result=json.readTree(response.body());
            String url=result.path("url").asText();
            assertThat(url).startsWith(fixture.origin()+"/bird/v1/oss/uploads/").doesNotContain("?");
            assertThat(response.headers().firstValue("Location")).contains(url);
            var downloaded=client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.headers().firstValue("Content-Type")).contains("image/png");
            assertThat(downloaded.body()).containsExactly(bytes);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(downloaded.body())))
                .isEqualTo(result.path("sha256").asText());
            assertThat(fixture.publicRequests.get()).isGreaterThanOrEqualTo(2);
            assertThat(fixture.signedRequests.get()).isGreaterThanOrEqualTo(3);
            var malformed=HttpRequest.newBuilder(request.uri()).header("Authorization","Bearer "+token)
                .header("Content-Type","multipart/form-data").POST(HttpRequest.BodyPublishers.ofString("invalid")).build();
            var malformedResponse=client.send(malformed,HttpResponse.BodyHandlers.ofByteArray());
            assertThat(malformedResponse.statusCode()).isEqualTo(400);
            assertThat(json.readTree(malformedResponse.body()).path("code").asText()).isEqualTo("PUBLIC_UPLOAD_INVALID_MULTIPART");
            var oversized=HttpRequest.newBuilder(request.uri()).header("Authorization","Bearer "+token)
                .header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofString("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.png\"\r\nContent-Type: image/png\r\n\r\n"),
                    HttpRequest.BodyPublishers.ofByteArray(new byte[PublicUploadService.MAX_BYTES+1]),
                    HttpRequest.BodyPublishers.ofString("\r\n--"+boundary+"--\r\n"))).build();
            assertThat(client.send(oversized,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(413);
        }
    }

    @Test void realHttpVideoAtFourteenAndFiftyMiBUsesStreamingSdkTlsAndRange() throws Exception {
        fixture.applicationPort=port;
        long actor=Math.abs(UUID.randomUUID().getMostSignificantBits()%1000000000L)+1000;
        db.update("INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag) VALUES(?,?,?,?,'0','0')",actor,"video_http_"+actor,"fixture","unused");
        db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",actor);
        String token="adm_"+users.randomKey(32);
        db.update("INSERT INTO admin_session(token_hash,user_id,expires_at) VALUES(?,?,?)",RegistrationService.digest(token),actor,java.sql.Timestamp.from(Instant.now().plusSeconds(600)));
        byte[] clip;try(var input=getClass().getResourceAsStream("/public-upload-fixture.mp4")){clip=input.readAllBytes();}
        try(var client=HttpClient.newBuilder().sslContext(fixture.ssl).followRedirects(HttpClient.Redirect.NEVER).build()){
            for(long size:new long[]{14L*1024*1024,PublicUploadService.MAX_BYTES,PublicUploadService.MAX_BYTES+1L}){
                Path file=Files.createTempFile("xcwh-video-http-input-", ".mp4"),download=Files.createTempFile("xcwh-video-http-output-", ".mp4");
                try {
                    try(var random=new RandomAccessFile(file.toFile(),"rw")){random.write(clip);random.writeInt((int)(size-clip.length));random.writeBytes("free");random.setLength(size);}
                    String boundary="fixture"+users.randomKey(8);
                    var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.mp4\"\r\nContent-Type: video/mp4\r\n\r\n"),HttpRequest.BodyPublishers.ofFile(file),HttpRequest.BodyPublishers.ofString("\r\n--"+boundary+"--\r\n"));
                    var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/admin/v1/oss/uploads")).header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build(),HttpResponse.BodyHandlers.ofByteArray());
                    if(size>PublicUploadService.MAX_BYTES){assertThat(response.statusCode()).isEqualTo(413);continue;}
                    assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(201);
                    var result=json.readTree(response.body());String url=result.path("url").asText();
                    assertThat(result.path("mime").asText()).isEqualTo("video/mp4");assertThat(result.path("size").asLong()).isEqualTo(size);
                    var get=client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofFile(download));
                    assertThat(get.statusCode()).isEqualTo(200);assertThat(PublicUploadFiles.sha256(download)).isEqualTo(PublicUploadFiles.sha256(file)).isEqualTo(result.path("sha256").asText());
                    var head=client.send(HttpRequest.newBuilder(URI.create(url)).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
                    assertThat(head.statusCode()).isEqualTo(200);assertThat(head.headers().firstValueAsLong("Content-Length")).hasValue(size);
                    var range=client.send(HttpRequest.newBuilder(URI.create(url)).header("Range","bytes=0-31").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                    assertThat(range.statusCode()).isEqualTo(206);assertThat(range.body()).containsExactly(Arrays.copyOf(clip,32));
                }finally{Files.deleteIfExists(file);Files.deleteIfExists(download);}
            }
        }
    }

    static final class Fixture implements AutoCloseable {
        final HttpServer oss;
        final HttpsServer publicServer;
        final SSLContext ssl;
        final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
        final Map<String,Path> objects=new ConcurrentHashMap<>();
        final Path storage=Files.createTempDirectory("xcwh-oss-http-fixture-");
        final java.util.concurrent.atomic.AtomicInteger publicRequests=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger signedRequests=new java.util.concurrent.atomic.AtomicInteger();
        final HttpClient upstream=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        volatile int applicationPort;

        Fixture() throws Exception {
            Path key=Files.createTempFile("xcwh-upload-tls-",".p12");Files.delete(key);
            String password=UUID.randomUUID().toString();
            try {
                var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                    "-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-storetype","PKCS12",
                    "-keystore",key.toString(),"-storepass",password,"-keypass",password,
                    "-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","2","-noprompt")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if(process.waitFor()!=0)throw new IOException("Fixture TLS key generation failed");
                var keys=KeyStore.getInstance("PKCS12");
                try(var input=Files.newInputStream(key)) {keys.load(input,password.toCharArray());}
                var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());
                var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
                ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
            } finally {Files.deleteIfExists(key);}
            oss=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            oss.setExecutor(executor);
            oss.createContext("/",exchange->{
                try {
                    String path=exchange.getRequestURI().getPath();
                    if(!path.startsWith("/fixture-public-images/content/public-uploads/") ||
                        !Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),"").startsWith("OSS4-HMAC-SHA256")) {
                        exchange.sendResponseHeaders(403,-1);return;
                    }
                    signedRequests.incrementAndGet();
                    if(exchange.getRequestMethod().equals("PUT")) {
                        Path object=Files.createTempFile(storage,"object-", ".bin");
                        try(var target=Files.newOutputStream(object)){exchange.getRequestBody().transferTo(target);}
                        objects.put(path,object);
                        exchange.getResponseHeaders().set("ETag","\"5289DF737DF57326FCDD22597AFB1FAC\"");
                        exchange.sendResponseHeaders(200,-1);
                    } else {
                        Path object=objects.get(path);
                        exchange.getResponseHeaders().set("Content-Type","application/octet-stream");
                        exchange.sendResponseHeaders(200,Files.size(object));try(var input=Files.newInputStream(object)){input.transferTo(exchange.getResponseBody());}
                    }
                } finally {exchange.close();}
            });
            oss.start();
            publicServer=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
            publicServer.setHttpsConfigurator(new HttpsConfigurator(ssl));
            publicServer.setExecutor(executor);
            publicServer.createContext("/bird/v1/oss/uploads/",exchange->{
                try {
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                    assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
                    publicRequests.incrementAndGet();
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+applicationPort+exchange.getRequestURI().getPath()))
                        .method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.noBody());
                    String range=exchange.getRequestHeaders().getFirst("Range");if(range!=null)request.header("Range",range);
                    var response=upstream.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
                    for(String header:List.of("Content-Type","Content-Length","Content-Range","ETag"))response.headers().firstValue(header).ifPresent(v->exchange.getResponseHeaders().set(header,v));
                    long length=response.headers().firstValueAsLong("Content-Length").orElse(0);
                    boolean head=exchange.getRequestMethod().equals("HEAD");
                    exchange.sendResponseHeaders(response.statusCode(),head||length==0?-1:length);
                    try(var input=response.body()){if(!head&&length>0)input.transferTo(exchange.getResponseBody());}
                } catch(InterruptedException e) {Thread.currentThread().interrupt();}
                finally {exchange.close();}
            });
            publicServer.start();
        }
        String origin() {return "https://localhost:"+publicServer.getAddress().getPort();}
        @Override public void close() {
            publicServer.stop(0);oss.stop(0);upstream.close();executor.close();
            try(var files=Files.list(storage)){for(Path file:files.toList())Files.deleteIfExists(file);}catch(IOException e){throw new UncheckedIOException(e);}
            try{Files.deleteIfExists(storage);}catch(IOException e){throw new UncheckedIOException(e);}
        }
    }
}
