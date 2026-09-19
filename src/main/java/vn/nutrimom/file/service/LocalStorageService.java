package vn.nutrimom.file.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Local/dev storage adapter. Production object storage and signed provider URLs
 * remain an infrastructure decision and must replace this adapter before release.
 */
@Service
public class LocalStorageService implements StorageService {
    private final Path root;

    public LocalStorageService(@Value("${app.storage.local-root:${java.io.tmpdir}/nutrimom-storage}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    @Override
    public String uploadUrl(String fileId) { return "/api/v1/files/" + fileId + "/content"; }

    @Override
    public void put(String storageKey, byte[] content) {
        try {
            Path path = resolve(storageKey);
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write local upload", ex);
        }
    }

    @Override
    public java.util.Optional<StoredObject> head(String storageKey) {
        try {
            Path path = resolve(storageKey);
            if (!Files.exists(path)) return java.util.Optional.empty();
            return java.util.Optional.of(new StoredObject(Files.size(path), Files.readAllBytes(path)));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to inspect local upload", ex);
        }
    }

    @Override
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
