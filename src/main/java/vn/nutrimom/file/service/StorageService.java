package vn.nutrimom.file.service;

import java.util.Optional;
import vn.nutrimom.file.domain.StorageProvider;

public interface StorageService {
    default String uploadUrl(String fileId) { return "/api/v1/files/" + fileId + "/content"; }
    Location newLocation(String storageKey);
    void put(Location location, byte[] content);
    Optional<StoredObject> head(Location location);
    byte[] read(Location location);

    record Location(StorageProvider provider, String bucket, String key) { }
    record StoredObject(long sizeBytes, byte[] content) { }
}
