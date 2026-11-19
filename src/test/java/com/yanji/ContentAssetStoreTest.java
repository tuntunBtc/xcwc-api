package com.yanji;

import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class ContentAssetStoreTest {
    static final String ID="a".repeat(32);
    @TempDir Path root;

    @Test void explicitLocalOssModeRequiresTemporaryCredentialsAndRejectsUntrustedEndpoint() {
        var env=new org.springframework.mock.env.MockEnvironment()
            .withProperty("management-content.storage","oss")
            .withProperty("management-content.oss.credentials","local-sts")
            .withProperty("management-content.oss.region","cn-hangzhou")
            .withProperty("management-content.oss.endpoint","https://example.invalid");
        var config=new ContentStorageConfiguration();
        assertThatThrownBy(()->config.contentAssetStore(env))
            .hasMessage("Local OSS requires the regional HTTPS endpoint");
        env.setProperty("management-content.oss.endpoint","https://oss-cn-hangzhou.aliyuncs.com");
        env.setProperty("CONTENT_OSS_STS_KEY_ID","fixture-id");
        env.setProperty("CONTENT_OSS_STS_KEY_SECRET","fixture-secret");
        assertThatThrownBy(()->config.contentAssetStore(env))
            .hasMessageContaining("CONTENT_OSS_STS_TOKEN");
    }

    @Test void localPreservesBinaryDataAndRejectsTraversal() throws Exception {
        var store=new LocalContentAssetStore(root);
        byte[] bytes={0,1,-1};
        store.put("originals/"+ID,bytes,"image/png");
        assertThat(store.read("originals/"+ID)).containsExactly(bytes);
        assertThat(store.find(ID)).isEmpty();
        assertThatThrownBy(()->store.find("../secret")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ossOnlyTreatsMissingKeyAsAbsentAndDoesNotHideAccessFailure() throws Exception {
        OSS client=mock(OSS.class);var store=new OssContentAssetStore(client,"test-bucket","content/");
        var missing=mock(OSSException.class);when(missing.getErrorCode()).thenReturn("NoSuchKey");
        when(client.getObject("test-bucket","content/"+ID)).thenThrow(missing);
        assertThat(store.find(ID)).isEmpty();
        var denied=mock(OSSException.class);when(denied.getErrorCode()).thenReturn("AccessDenied");
        doThrow(denied).when(client).getObject("test-bucket","content/"+ID);
        assertThatThrownBy(()->store.find(ID)).isInstanceOf(IOException.class);
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(denied);
        assertThatThrownBy(()->store.put(ID,new byte[]{1},"image/png")).isInstanceOf(IOException.class);
    }

    @Test void ossWritesExactBytesWithIntegrityAndPrivateCacheMetadata() throws Exception {
        OSS client=mock(OSS.class);var store=new OssContentAssetStore(client,"test-bucket","content/");
        byte[] bytes={5,7,9};store.put(ID,bytes,"image/png");
        var capture=ArgumentCaptor.forClass(PutObjectRequest.class);verify(client).putObject(capture.capture());
        var request=capture.getValue();
        assertThat(request.getBucketName()).isEqualTo("test-bucket");
        assertThat(request.getKey()).isEqualTo("content/"+ID);
        assertThat(request.getInputStream().readAllBytes()).containsExactly(bytes);
        assertThat(request.getMetadata().getContentMD5()).isNotBlank();
        assertThat(request.getMetadata().getUserMetadata().get("sha256")).hasSize(64);
        assertThat(request.getMetadata().getCacheControl()).isEqualTo("private, no-store");
    }

    @Test void migrationResumesButRefusesToOverwriteConflictingRemoteObject() throws Exception {
        ContentAssetStore store=mock(ContentAssetStore.class);byte[] data={1,2};
        when(store.find(ID)).thenReturn(Optional.empty());when(store.read(ID)).thenReturn(data);
        ContentAssetMigration.copyVerified(store,ID,data,"image/png");verify(store).put(ID,data,"image/png");
        reset(store);when(store.find(ID)).thenReturn(Optional.of(data));
        ContentAssetMigration.copyVerified(store,ID,data,"image/png");verify(store,never()).put(anyString(),any(),anyString());
        when(store.find(ID)).thenReturn(Optional.of(new byte[]{3}));
        assertThatThrownBy(()->ContentAssetMigration.copyVerified(store,ID,data,"image/png")).isInstanceOf(IOException.class);
        verify(store,never()).put(anyString(),any(),anyString());
    }

    @Test void realSdkCanSignWriteAndReadAgainstIsolatedHttpFixture() throws Exception {
        var received=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var authorization=new java.util.concurrent.atomic.AtomicReference<String>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                if("PUT".equals(exchange.getRequestMethod())) {
                    received.set(exchange.getRequestBody().readAllBytes());
                    exchange.getResponseHeaders().set("ETag","\"5289DF737DF57326FCDD22597AFB1FAC\"");
                    exchange.sendResponseHeaders(200,-1);
                } else {
                    byte[] body=received.get();
                    exchange.getResponseHeaders().set("Content-Type","image/png");
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                }
            } finally {exchange.close();}
        });
        server.start();
        var config=new ClientBuilderConfiguration();config.setSignatureVersion(com.aliyun.oss.common.comm.SignVersion.V4);
        OSS client=OSSClientBuilder.create().endpoint("http://127.0.0.1:"+server.getAddress().getPort())
            .region("cn-hangzhou").credentialsProvider(new com.aliyun.oss.common.auth.DefaultCredentialProvider("fixture-id","fixture-secret"))
            .clientConfiguration(config).build();
        try(var store=new OssContentAssetStore(client,"test-bucket","content/")) {
            byte[] body={1,2,3};store.put(ID,body,"image/png");
            assertThat(received.get()).containsExactly(body);
            assertThat(store.read(ID)).containsExactly(body);
            assertThat(authorization.get()).startsWith("OSS4-HMAC-SHA256");
        } finally {server.stop(0);}
    }
}
