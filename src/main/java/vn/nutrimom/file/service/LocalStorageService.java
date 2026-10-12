package vn.nutrimom.file.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Local development storage and the reader for files created before private R2 was enabled.
 */
@Service
public class LocalStorageService {
    private final Path root;

    public LocalStorageService(@Value("${app.storage.local-root:${java.io.tmpdir}/nutrimom-storage}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    public void put(String storageKey, byte[] content) {
        try {
            Path path = resolve(storageKey);
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write local upload", ex);
        }
    }

    public java.util.Optional<StorageService.StoredObject> head(String storageKey) {
        try {
            Path path = resolve(storageKey);
            if (!Files.exists(path)) return java.util.Optional.empty();
            return java.util.Optional.of(new StorageService.StoredObject(Files.size(path), Files.readAllBytes(path)));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to inspect local upload", ex);
        }
    }

    public byte[] read(String storageKey) {
        try { return Files.readAllBytes(resolve(storageKey)); }
        catch (IOException ex) { throw new IllegalStateException("Unable to read local file", ex); }
    }

    private Path resolve(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return path;
    }
}
