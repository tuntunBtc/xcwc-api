package com.yanji;

import java.io.*;
import java.nio.file.*;
import java.util.Optional;

/** Only objects registered by the public operational upload workflow. Production uses file streams. */
public interface PublicUploadStore {
    void putFile(String filename, Path file, String mime) throws IOException;
    boolean downloadTo(String filename, Path target) throws IOException;

    // Small adapters retained for SDK regression fixtures; the application never uses these byte-array methods.
    default void put(String filename, byte[] bytes, String mime) throws IOException {
        Path file=Files.createTempFile("xcwh-public-adapter-", ".bin");
        try {Files.write(file,bytes);putFile(filename,file,mime);}finally{Files.deleteIfExists(file);}
    }
    default Optional<byte[]> find(String filename) throws IOException {
        Path file=Files.createTempFile("xcwh-public-adapter-", ".bin");
        try{return downloadTo(filename,file)?Optional.of(Files.readAllBytes(file)):Optional.empty();}
        finally{Files.deleteIfExists(file);}
    }
    static String checkedFilename(String filename) {
        if(filename==null||!filename.matches("[0-9a-f]{32}\\.(png|jpg|webp|mp4)"))throw new IllegalArgumentException("Invalid public upload filename");
        return filename;
    }
}
