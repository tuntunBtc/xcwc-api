package com.yanji;

import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class OssPublicUploadStore implements PublicUploadStore, AutoCloseable {
    static final String PREFIX="content/public-uploads/";
    private final OSS client;
    private final String bucket;
    public OssPublicUploadStore(OSS client,String bucket) {
        if(bucket==null||!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))throw new IllegalArgumentException("Invalid upload bucket");
        this.client=client;this.bucket=bucket;
    }
    @Override public void putFile(String filename,Path file,String mime) throws IOException {
        String key=PREFIX+PublicUploadStore.checkedFilename(filename);long size=Files.size(file);
        if(size>PublicUploadService.MAX_BYTES)throw new IOException("Object exceeds upload limit");
        var metadata=new ObjectMetadata();metadata.setContentLength(size);metadata.setContentType(mime);
        metadata.setCacheControl("public, max-age=31536000, immutable");
        var md5=PublicUploadFiles.digest("MD5");
        try(var input=new DigestInputStream(Files.newInputStream(file),md5)){input.transferTo(OutputStream.nullOutputStream());}
        metadata.setContentMD5(Base64.getEncoder().encodeToString(md5.digest()));
        metadata.addUserMetadata("sha256",PublicUploadFiles.sha256(file));
        try(var input=Files.newInputStream(file)) {
            var put=new PutObjectRequest(bucket,key,input,metadata);put.addHeader("x-oss-forbid-overwrite","true");client.putObject(put);
        } catch(OSSException|ClientException e){throw new IOException("OSS public upload failed",e);}
    }
    @Override public boolean downloadTo(String filename,Path target) throws IOException {
        String key=PREFIX+PublicUploadStore.checkedFilename(filename);
        try(OSSObject object=client.getObject(bucket,key)) {
            long expected=object.getObjectMetadata().getContentLength();
            if(expected<0||expected>PublicUploadService.MAX_BYTES)throw new IOException("Object exceeds upload limit");
            try(var output=Files.newOutputStream(target)) {
                long actual=PublicUploadFiles.copy(object.getObjectContent(),output,PublicUploadService.MAX_BYTES);
                if(actual!=expected)throw new IOException("OSS object length mismatch");
            }
            return true;
        }catch(OSSException e){if("NoSuchKey".equals(e.getErrorCode()))return false;throw new IOException("OSS public read failed",e);}
        catch(ClientException e){throw new IOException("OSS public read failed",e);}
    }
    @Override public void close(){client.shutdown();}
}
