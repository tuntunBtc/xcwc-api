package com.yanji;

import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** No local fallback: an OSS outage must not silently create local-only assets. */
public final class OssContentAssetStore implements ContentAssetStore, AutoCloseable {
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private final OSS client;
    private final String bucket;
    private final String prefix;

    public OssContentAssetStore(OSS client, String bucket, String prefix) {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))
            throw new IllegalArgumentException("Invalid OSS bucket");
        if (prefix == null || !prefix.matches("[a-zA-Z0-9_-]+/"))
            throw new IllegalArgumentException("Invalid OSS content prefix");
        this.client = client; this.bucket = bucket; this.prefix = prefix;
    }

    @Override public Optional<byte[]> find(String key) throws IOException {
        String objectKey = prefix + ContentAssetStore.checkedKey(key);
        try (OSSObject object = client.getObject(bucket, objectKey)) {
            if (object.getObjectMetadata().getContentLength() > MAX_BYTES)
                throw new IOException("Content object exceeds size limit");
            byte[] data = object.getObjectContent().readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) throw new IOException("Content object exceeds size limit");
            return Optional.of(data);
        } catch (OSSException e) {
            if ("NoSuchKey".equals(e.getErrorCode())) return Optional.empty();
            throw new IOException("OSS content read failed", e);
        } catch (ClientException e) { throw new IOException("OSS content read failed", e); }
    }

    @Override public void put(String key, byte[] bytes, String mime) throws IOException {
        String objectKey = prefix + ContentAssetStore.checkedKey(key);
        if (bytes.length > MAX_BYTES) throw new IOException("Content object exceeds size limit");
        var metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(mime);
        metadata.setCacheControl("private, no-store");
        try {
            metadata.setContentMD5(Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes)));
            metadata.addUserMetadata("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        try {
            client.putObject(new PutObjectRequest(bucket, objectKey, new ByteArrayInputStream(bytes), metadata));
        } catch (OSSException | ClientException e) { throw new IOException("OSS content write failed", e); }
    }

    @Override public void close() { client.shutdown(); }
}
