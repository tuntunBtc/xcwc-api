package com.yanji;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class HttpsPublicUploadVerifier implements PublicUploadVerifier, AutoCloseable {
    private final HttpClient http;
    private final Duration timeout;

    HttpsPublicUploadVerifier() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(180));
    }

    HttpsPublicUploadVerifier(HttpClient http, Duration timeout) {
        this.http = http;
        this.timeout = timeout;
    }

    @Override public void verify(String url, byte[] expected, String mime) throws IOException {
        verifyDigest(url,expected.length,HexFormat.of().formatHex(PublicUploadFiles.digest("SHA-256").digest(expected)),mime);
    }
    @Override public void verifyFile(String url,java.nio.file.Path expected,String mime) throws IOException {
        verifyDigest(url,java.nio.file.Files.size(expected),PublicUploadFiles.sha256(expected),mime);
    }
    private void verifyDigest(String url,long length,String sha256,String mime) throws IOException {
        var body=new DigestBody(length);
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
        var response=http.sendAsync(request,info->body);
        try {
            var result=response.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            String actualMime=result.headers().firstValue("Content-Type").orElse("").split(";")[0].strip();
            if(result.statusCode()!=200||!actualMime.equalsIgnoreCase(mime)||result.body().length()!=length||!result.body().sha256().equals(sha256))
                throw new IOException("Anonymous upload verification failed");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Upload verification interrupted",e);}
        catch(ExecutionException|TimeoutException e){throw new IOException("Upload verification unavailable",e);}
        finally{body.cancel();response.cancel(true);}
    }
    private record Digest(long length,String sha256) {}
    private static final class DigestBody implements HttpResponse.BodySubscriber<Digest> {
        private final long limit;private long count;
        private final java.security.MessageDigest digest=PublicUploadFiles.digest("SHA-256");
        private final CompletableFuture<Digest> completed=new CompletableFuture<>();
        private Flow.Subscription subscription;
        DigestBody(long limit){this.limit=limit;}
        @Override public CompletionStage<Digest> getBody(){return completed;}
        @Override public synchronized void onSubscribe(Flow.Subscription value){subscription=value;if(completed.isDone())value.cancel();else value.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){
                int n=buffer.remaining();
                if(n>limit-count){completed.completeExceptionally(new IOException("Public object exceeds expected length"));cancel();return;}
                count+=n;digest.update(buffer);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error){completed.completeExceptionally(error);}
        @Override public void onComplete(){completed.complete(new Digest(count,HexFormat.of().formatHex(digest.digest())));}
        synchronized void cancel(){completed.cancel(false);if(subscription!=null)subscription.cancel();}
    }

    @Override public void close() { http.close(); }
}
