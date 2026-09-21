package vn.nutrimom.file.service;

import java.util.Optional;

public interface StorageService {
    String uploadUrl(String fileId);
    void put(String storageKey, byte[] content);
    Optional<StoredObject> head(String storageKey);
    byte[] read(String storageKey);

    record StoredObject(long sizeBytes, byte[] content) { }
}
