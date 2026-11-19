package com.yanji;

import java.io.IOException;
import java.util.Optional;

/** Private content objects only; authorization remains in ContentService. */
public interface ContentAssetStore {
    Optional<byte[]> find(String key) throws IOException;
    void put(String key, byte[] bytes, String mime) throws IOException;

    static String checkedKey(String key) {
        if (key == null || !key.matches("(?:originals/)?[0-9a-f]{32}|thumbnails/[0-9a-f]{32}\\.png"))
            throw new IllegalArgumentException("Invalid content object key");
        return key;
    }

    default byte[] read(String key) throws IOException {
        return find(key).orElseThrow(() -> new java.io.FileNotFoundException("Content object missing"));
    }
}
