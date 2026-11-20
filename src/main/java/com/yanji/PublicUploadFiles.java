package com.yanji;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

final class PublicUploadFiles {
    private PublicUploadFiles() {}
    static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer=new byte[65536];long count=0;int n;
        while((n=input.read(buffer))!=-1) {
            if(n>limit-count)throw new LimitExceeded();
            output.write(buffer,0,n);count+=n;
        }
        return count;
    }
    static String sha256(Path file) throws IOException {
        var digest=digest("SHA-256");
        try(var input=new DigestInputStream(Files.newInputStream(file),digest)) {input.transferTo(OutputStream.nullOutputStream());}
        return HexFormat.of().formatHex(digest.digest());
    }
    static MessageDigest digest(String name) {
        try{return MessageDigest.getInstance(name);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static final class LimitExceeded extends IOException {}
}
