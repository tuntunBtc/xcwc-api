package com.yanji;

import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;

public final class LocalContentAssetStore implements ContentAssetStore {
    private final Path directory;

    public LocalContentAssetStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    @Override public Optional<byte[]> find(String key) throws IOException {
        Path file = directory.resolve(ContentAssetStore.checkedKey(key));
        try { return Optional.of(Files.readAllBytes(file)); }
        catch (NoSuchFileException e) { return Optional.empty(); }
    }

    @Override public void put(String key, byte[] bytes, String mime) throws IOException {
        Path file = directory.resolve(ContentAssetStore.checkedKey(key));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "content-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
