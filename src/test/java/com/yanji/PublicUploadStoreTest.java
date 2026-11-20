package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class PublicUploadStoreTest {
    static final String NAME = "0123456789abcdef0123456789abcdef.png";
    static final String BUCKET = "fixture-public-uploads";

    @Test void ossWritesOnlyItsDedicatedPrefixWithoutAclOrOverwriteAndReadsBack() throws Exception {
        OSS client=mock(OSS.class);byte[] bytes={1,2,3};
        var store=new OssPublicUploadStore(client,BUCKET);
        var sent=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        doAnswer(call->{sent.set(((PutObjectRequest)call.getArgument(0)).getInputStream().readAllBytes());return null;}).when(client).putObject(any(PutObjectRequest.class));
        store.put(NAME,bytes,"image/png");
        var request=org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        var put=request.getValue();
        assertThat(put.getKey()).isEqualTo("content/public-uploads/"+NAME);
        assertThat(put.getHeaders()).containsEntry("x-oss-forbid-overwrite","true");
        assertThat(sent.get()).containsExactly(bytes);
        assertThat(put.getMetadata().getContentType()).isEqualTo("image/png");
        assertThat(put.getMetadata().getContentMD5()).isNotBlank();
        var object=new OSSObject();var metadata=new ObjectMetadata();metadata.setContentLength(bytes.length);
        object.setObjectMetadata(metadata);object.setObjectContent(new ByteArrayInputStream(bytes));
        when(client.getObject(BUCKET,"content/public-uploads/"+NAME)).thenReturn(object);
        assertThat(store.find(NAME).orElseThrow()).containsExactly(bytes);
        assertThatThrownBy(()->store.find("../originals/"+NAME)).isInstanceOf(IllegalArgumentException.class);
        store.close();verify(client).shutdown();
    }

    @Test void missingForbiddenAndOverLimitOssReadsAreDistinguished() throws Exception {
        OSS client=mock(OSS.class);var store=new OssPublicUploadStore(client,BUCKET);
        OSSException missing=new OSSException("fixture","NoSuchKey","request","host",null,null,null);
        doThrow(missing).when(client).getObject(anyString(),anyString());
        assertThat(store.find(NAME)).isEmpty();
        OSSException forbidden=new OSSException("fixture","AccessDenied","request","host",null,null,null);
        doThrow(forbidden).when(client).getObject(anyString(),anyString());
        assertThatThrownBy(()->store.find(NAME)).isInstanceOf(IOException.class);
        var object=new OSSObject();var metadata=new ObjectMetadata();metadata.setContentLength(PublicUploadService.MAX_BYTES+1L);
        object.setObjectMetadata(metadata);object.setObjectContent(new ByteArrayInputStream(new byte[0]));
        doReturn(object).when(client).getObject(anyString(),anyString());
        assertThatThrownBy(()->store.find(NAME)).isInstanceOf(IOException.class);
    }

    @Test void realOssSdkSignsTheNewPrefixAndRoundTripsBytesWithoutAclCalls() throws Exception {
        var saved=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var paths=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                paths.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath());
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("OSS4-HMAC-SHA256");
                assertThat(exchange.getRequestURI().getQuery()).isNull();
                if(exchange.getRequestMethod().equals("PUT")) {
                    assertThat(exchange.getRequestHeaders().getFirst("x-oss-forbid-overwrite")).isEqualTo("true");
                    assertThat(exchange.getRequestHeaders().getFirst("x-oss-object-acl")).isNull();
                    saved.set(exchange.getRequestBody().readAllBytes());
                    exchange.getResponseHeaders().set("ETag","\"5289DF737DF57326FCDD22597AFB1FAC\"");
                    exchange.sendResponseHeaders(200,-1);
                } else {
                    var body=saved.get();exchange.getResponseHeaders().set("Content-Type","image/png");
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                }
            } finally {exchange.close();}
        });
        server.start();
        var config=new ClientBuilderConfiguration();
        config.setSignatureVersion(com.aliyun.oss.common.comm.SignVersion.V4);
        OSS client=OSSClientBuilder.create().endpoint("http://127.0.0.1:"+server.getAddress().getPort())
            .region("cn-hangzhou").credentialsProvider(new com.aliyun.oss.common.auth.DefaultCredentialProvider("fixture-id","fixture-secret")).clientConfiguration(config).build();
        try(var store=new OssPublicUploadStore(client,BUCKET)) {
            byte[] body={1,2,3};store.put(NAME,body,"image/png");
            assertThat(store.find(NAME).orElseThrow()).containsExactly(body);
            assertThat(paths).allMatch(p->p.endsWith("/"+BUCKET+"/content/public-uploads/"+NAME));
        } finally {server.stop(0);}
    }

    @Test void publicOriginCannotCarrySignedQueriesCredentialsPathsOrInternalEndpoint() {
        assertThat(PublicUploadService.checkedOrigin("https://assets.example.test/")).isEqualTo("https://assets.example.test");
        for(String bad:List.of("http://assets.example.test","https://a:b@assets.example.test",
            "https://assets.example.test/?signature=x","https://assets.example.test/files",
            "https://oss-cn-hangzhou-internal.aliyuncs.com","https://assets.example.test:65536")) {
            assertThatThrownBy(()->PublicUploadService.checkedOrigin(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void anonymousVerificationChecksActualHttpStatusMimeBytesLimitsAndDeadline() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        byte[] bytes={1,2,3};
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();
            try {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
                exchange.getResponseHeaders().set("Content-Type",path.equals("/bad-mime")?"text/html":"image/png");
                if(path.equals("/redirect"))exchange.getResponseHeaders().set("Location","/ok");
                if(path.equals("/stall")) {
                    exchange.sendResponseHeaders(200,0);
                    Thread.sleep(3000);
                } else {
                    byte[] data=path.equals("/long")?new byte[4096]:path.equals("/wrong")?new byte[]{4,5,6}:bytes;
                    exchange.sendResponseHeaders(path.equals("/redirect")?302:200,data.length);
                    exchange.getResponseBody().write(data);
                }
            } catch(InterruptedException e) {Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });
        server.start();
        try(var verifier=new HttpsPublicUploadVerifier(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),Duration.ofSeconds(2))) {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            verifier.verify(base+"/ok",bytes,"image/png");
            for(String path:List.of("/bad-mime","/redirect","/long","/wrong","/stall")) {
                assertThatThrownBy(()->verifier.verify(base+path,bytes,"image/png")).isInstanceOf(IOException.class);
            }
        } finally {server.stop(0);}
    }
}
